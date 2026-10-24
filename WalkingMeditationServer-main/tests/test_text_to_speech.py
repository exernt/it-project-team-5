import asyncio
import io
import unittest
from unittest.mock import Mock, patch
from botocore.exceptions import ClientError, NoCredentialsError
from fastapi.testclient import TestClient
from fastapi import Depends, HTTPException
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from route.dependencies import get_current_user
import main
from route.models import TTSRequest
from route.services.text_to_speech import generate_tts_mp3, create_polly_client

PAYLOAD = {"Text": '<speak>Walk.<break time="2500ms"/>Breathe.</speak>',
           "TextType": "ssml", "VoiceId": "Joanna", "LanguageCode": "en-US",
           "Engine": "standard", "OutputFormat": "mp3"}


class TtsServiceTests(unittest.IsolatedAsyncioTestCase):
    async def test_returns_mp3_closes_stream_and_client(self):
        stream = io.BytesIO(b"test-mp3")
        client = Mock()
        client.synthesize_speech.return_value = {"AudioStream": stream}
        with patch("route.services.text_to_speech.create_polly_client", return_value=client):
            response = await generate_tts_mp3(TTSRequest.model_validate(PAYLOAD), asyncio.Semaphore(1))
        self.assertEqual(response.body, b"test-mp3")
        self.assertEqual(response.media_type, "audio/mpeg")
        client.synthesize_speech.assert_called_once_with(**PAYLOAD)
        self.assertTrue(stream.closed)
        client.close.assert_called_once()

    async def test_empty_audio_and_read_failure_close_resources(self):
        for stream in [io.BytesIO(), Mock()]:
            if isinstance(stream, Mock):
                stream.read.side_effect = IOError("read failed")
            client = Mock()
            client.synthesize_speech.return_value = {"AudioStream": stream}
            with patch("route.services.text_to_speech.create_polly_client", return_value=client):
                with self.assertRaises((RuntimeError, IOError)):
                    await generate_tts_mp3(TTSRequest.model_validate(PAYLOAD), asyncio.Semaphore(1))
            client.close.assert_called_once()
            if isinstance(stream, Mock):
                stream.close.assert_called_once()
            else:
                self.assertTrue(stream.closed)


class TtsEndpointTests(unittest.TestCase):
    @patch.dict("os.environ", {"AWS_REGION": "ap-southeast-2"})
    def test_client_uses_region_bounded_timeouts_and_sdk_credential_chain(self):
        with patch("boto3.client") as factory:
            create_polly_client()
        args, options = factory.call_args
        self.assertEqual(args, ("polly",))
        self.assertEqual(options["region_name"], "ap-southeast-2")
        self.assertEqual(options["config"].connect_timeout, 10)
        self.assertEqual(options["config"].read_timeout, 120)
        self.assertEqual(options["config"].retries["total_max_attempts"], 3)
        self.assertNotIn("aws_access_key_id", options)

    def post(self, payload=PAYLOAD, token="test-token"):
        # Do not start the database lifespan or contact AWS in these tests.
        async def authenticated_user(
            credentials: HTTPAuthorizationCredentials = Depends(HTTPBearer())
        ):
            if credentials.credentials != "test-token":
                raise HTTPException(status_code=401, detail="Invalid credentials")
            return object()

        headers = {"Authorization": "Bearer " + token} if token else {}
        with patch.dict(main.app.dependency_overrides, {get_current_user: authenticated_user}):
            return TestClient(main.app).post("/tts", json=payload, headers=headers)

    def test_android_ssml_payload_reaches_polly(self):
        client = Mock()
        client.synthesize_speech.return_value = {"AudioStream": io.BytesIO(b"mp3")}
        with patch("route.services.text_to_speech.create_polly_client", return_value=client):
            response = self.post()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, b"mp3")
        self.assertEqual(response.headers["content-type"], "audio/mpeg")
        client.synthesize_speech.assert_called_once_with(**PAYLOAD)

    def test_rejects_unauthorized_requests_before_aws(self):
        with patch("route.services.text_to_speech.create_polly_client") as factory:
            self.assertEqual(self.post(token="wrong").status_code, 401)
            self.assertEqual(self.post(token="").status_code, 401)
            factory.assert_not_called()

    def test_validates_limits_ssml_and_mp3_contract_before_aws(self):
        invalid = [
            {"text": "legacy Qwen payload"},
            {**PAYLOAD, "Text": "<speak>broken"},
            {**PAYLOAD, "Text": "<wrong>Walk.</wrong>"},
            {**PAYLOAD, "Text": "<speak><break time='1s'/></speak>"},
            {**PAYLOAD, "Text": "<speak>" + "a" * 60001 + "</speak>"},
            {**PAYLOAD, "Text": '<!DOCTYPE speak [<!ENTITY x "Walk">]><speak>&x;</speak>'},
            {**PAYLOAD, "OutputFormat": "pcm"},
        ]
        with patch("route.services.text_to_speech.create_polly_client") as factory:
            for payload in invalid:
                with self.subTest(payload=str(payload)[:80]):
                    self.assertEqual(self.post(payload).status_code, 422)
            factory.assert_not_called()

    def test_maps_provider_errors_without_exposing_details(self):
        for error, status, detail in [
            (NoCredentialsError(), 503, "Speech service unavailable"),
            (ClientError({"Error": {"Code": "EngineNotSupportedException", "Message": "private"}}, "SynthesizeSpeech"), 422, "Polly rejected the speech settings or text"),
            (ClientError({"Error": {"Code": "ThrottlingException", "Message": "private"}}, "SynthesizeSpeech"), 503, "Speech service unavailable"),
            (RuntimeError("private"), 502, "TTS generation failed"),
        ]:
            with patch("route.services.text_to_speech.create_polly_client", side_effect=error):
                response = self.post()
            self.assertEqual(response.status_code, status)
            self.assertEqual(response.json(), {"detail": detail})
