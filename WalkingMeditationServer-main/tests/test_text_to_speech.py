import asyncio
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, Mock, patch

from fastapi.testclient import TestClient

import main
from route.errors import TTSNotReadyError
from route.services.text_to_speech import generate_tts_mp3


class TtsServiceTests(unittest.IsolatedAsyncioTestCase):
    async def test_returns_audio_and_cleans_temporary_files(self):
        model = Mock()
        model.generate_custom_voice.return_value = ([[0.0] * 100], 24000)
        paths = []

        def convert(wav, mp3, bitrate):
            self.assertTrue(wav.is_file())
            self.assertEqual(bitrate, "192k")
            paths.extend([wav, mp3])
            mp3.write_bytes(b"test-mp3")

        response = await generate_tts_mp3(
            model, asyncio.Semaphore(1), "Walk gently.", "English", "Ryan",
            "Calm and clear", convert_wav_to_mp3=convert,
        )
        self.assertEqual(response.body, b"test-mp3")
        self.assertEqual(response.media_type, "audio/mpeg")
        model.generate_custom_voice.assert_called_once_with(
            text="Walk gently.", language="English", speaker="Ryan",
            instruct="Calm and clear",
        )
        self.assertTrue(all(not path.exists() for path in paths))

    async def test_missing_model_is_rejected(self):
        with self.assertRaises(TTSNotReadyError):
            await generate_tts_mp3(
                None, asyncio.Semaphore(1), "Walk.", "English", "Ryan", "",
            )

    async def test_empty_model_audio_is_rejected(self):
        model = Mock()
        model.generate_custom_voice.return_value = ([], 24000)
        with self.assertRaisesRegex(RuntimeError, "no audio"):
            await generate_tts_mp3(
                model, asyncio.Semaphore(1), "Walk.", "English", "Ryan", "",
            )


class TtsEndpointTests(unittest.TestCase):
    def test_android_payload_reaches_service_and_returns_audio(self):
        model = Mock()
        model.generate_custom_voice.return_value = ([[0.0] * 100], 24000)

        def convert(wav, mp3, bitrate):
            Path(mp3).write_bytes(b"test-mp3")

        async def synthesize(**kwargs):
            # Call the real service with its actual signature. This catches
            # unsupported endpoint keywords, including the removed speed field.
            return await generate_tts_mp3(**kwargs, convert_wav_to_mp3=convert)

        with patch.object(main, "get_tts_model", AsyncMock(return_value=model)), \
             patch("route.services.text_to_speech.generate_tts_mp3", side_effect=synthesize), \
             patch.dict("os.environ", {"APP_API_TOKEN": "test-token"}):
            # No lifespan context: tests do not connect to the configured database.
            client = TestClient(main.app)
            response = client.post("/tts", headers={"X-App-Token": "test-token"}, json={
                "text": "Walk gently.", "language": "English", "speaker": "Ryan",
                "instruct": "Calm and clear", "bitrate": "192k",
            })
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, b"test-mp3")
        self.assertEqual(response.headers["content-type"], "audio/mpeg")

    def test_provider_failure_is_reported_without_internal_details(self):
        with patch.object(main, "get_tts_model", AsyncMock(side_effect=RuntimeError("private detail"))), \
             patch.dict("os.environ", {"APP_API_TOKEN": ""}):
            response = TestClient(main.app).post("/tts", json={"text": "Walk."})
        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.json(), {"detail": "TTS generation failed"})