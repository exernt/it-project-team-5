import asyncio
import io
import math
import struct
import subprocess
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import Mock, patch

from route.models import TTSRequest
from route.services.speech_chunks import split_speech
from route.services.text_to_speech import generate_tts_mp3, encode_pcm_mp3, PollyUnavailableError, PollyRequestError, MAX_SYNTHESIS_CHUNKS


class SpeechChunkTests(unittest.TestCase):
    def assert_chunks(self, source):
        chunks = split_speech(source, "ssml")
        roots = [ET.fromstring(chunk) for chunk in chunks]
        self.assertEqual("".join(ET.fromstring(source).itertext()),
                         "".join("".join(root.itertext()) for root in roots))
        for chunk, root in zip(chunks, roots):
            self.assertLessEqual(len(chunk), 6000)
            self.assertLessEqual(len("".join(root.itertext())), 3000)
            self.assertTrue("".join(root.itertext()).strip())
        return roots

    def test_short_request_and_exact_limits_remain_unchanged(self):
        for text in ["<speak>Walk.</speak>", "<speak>" + "a" * 3000 + "</speak>"]:
            self.assertEqual([text], split_speech(text, "ssml"))
        self.assertEqual(["a" * 3000], split_speech("a" * 3000, "text"))

    def test_long_plain_text_and_unbroken_unicode_are_lossless(self):
        for text in ["Walk gently. Breathe slowly.\n\n" * 400, "\U0001f333" * 7001]:
            chunks = split_speech(text, "text")
            self.assertGreater(len(chunks), 1)
            self.assertEqual(text, "".join(chunks))
            self.assertTrue(all(len(c) <= 3000 for c in chunks))

    def test_ssml_nested_wrappers_entities_and_pauses_survive_boundaries(self):
        source = ('<speak><prosody rate="slow">' +
                  ('A &amp; B walk. ' * 220 + '<break time="2500ms"/>') * 3 +
                  '</prosody>Finish.</speak>')
        roots = self.assert_chunks(source)
        self.assertGreater(len(roots), 1)
        self.assertEqual(3, sum(len(root.findall(".//break")) for root in roots))
        for root in roots:
            self.assertEqual("slow", root.find("prosody").attrib["rate"])

    def test_escaped_xml_and_leading_trailing_pauses(self):
        roots = self.assert_chunks('<speak><break time="1s"/>' + '&amp;' * 2500 +
                                   '<break time="2s"/></speak>')
        self.assertEqual(2, sum(len(root.findall("break")) for root in roots))

    def test_sentence_boundaries_are_preferred(self):
        sentence = "word " * 250 + ". "
        chunks = split_speech(sentence * 4, "text")
        self.assertTrue(all(chunk.rstrip().endswith(".") for chunk in chunks))

    def test_substitution_is_not_duplicated_when_surrounding_text_is_split(self):
        roots = self.assert_chunks('<speak>' + "Walk. " * 700 +
                                   '<sub alias="World Wide Web">WWW</sub>' + " Breathe." * 700 + '</speak>')
        self.assertEqual(1, sum(len(root.findall(".//sub")) for root in roots))


class ChunkSynthesisTests(unittest.IsolatedAsyncioTestCase):
    async def test_multiple_pcm_responses_encode_once_in_order(self):
        client = Mock()
        streams = []
        def respond(**kwargs):
            stream = io.BytesIO(bytes([len(streams) + 1, 0]))
            streams.append(stream)
            return {"AudioStream": stream}
        client.synthesize_speech.side_effect = respond
        payload = TTSRequest.model_validate({"Text": "Walk. " * 1500, "TextType": "text"})
        with patch("route.services.text_to_speech.create_polly_client", return_value=client), \
             patch("route.services.text_to_speech.require_encoder", return_value="ffmpeg"), \
             patch("route.services.text_to_speech.encode_pcm_mp3", return_value=b"joined-mp3") as encode:
            response = await generate_tts_mp3(payload, asyncio.Semaphore(1))
        calls = client.synthesize_speech.call_args_list
        self.assertGreater(len(calls), 1)
        self.assertEqual(payload.text, "".join(c.kwargs["Text"] for c in calls))
        for call in calls:
            self.assertEqual("pcm", call.kwargs["OutputFormat"])
            self.assertEqual("16000", call.kwargs["SampleRate"])
            self.assertEqual("Joanna", call.kwargs["VoiceId"])
        encode.assert_called_once_with(b"".join(bytes([i + 1, 0]) for i in range(len(streams))), "ffmpeg")
        self.assertEqual(b"joined-mp3", response.body)
        self.assertTrue(all(stream.closed for stream in streams))
        client.close.assert_called_once()

    async def test_chunk_cap_rejects_before_encoder_or_aws_and_accepts_boundary(self):
        from route.services.speech_chunks import SPOKEN_LIMIT
        oversized = TTSRequest.model_validate({"Text": "a" * (SPOKEN_LIMIT * MAX_SYNTHESIS_CHUNKS + 1)})
        with patch("route.services.text_to_speech.create_polly_client") as factory, \
             patch("route.services.text_to_speech.require_encoder") as encoder:
            with self.assertRaises(PollyRequestError):
                await generate_tts_mp3(oversized, asyncio.Semaphore(1))
            factory.assert_not_called()
            encoder.assert_not_called()
        client = Mock()
        client.synthesize_speech.side_effect = lambda **kw: {"AudioStream": io.BytesIO(b"\x00\x00")}
        boundary = TTSRequest.model_validate({"Text": "a" * (SPOKEN_LIMIT * MAX_SYNTHESIS_CHUNKS)})
        with patch("route.services.text_to_speech.create_polly_client", return_value=client), \
             patch("route.services.text_to_speech.require_encoder", return_value="ffmpeg"), \
             patch("route.services.text_to_speech.encode_pcm_mp3", return_value=b"joined"):
            response = await generate_tts_mp3(boundary, asyncio.Semaphore(1))
        self.assertEqual(MAX_SYNTHESIS_CHUNKS, client.synthesize_speech.call_count)
        self.assertEqual(b"joined", response.body)

    async def test_missing_encoder_fails_before_any_aws_request(self):
        payload = TTSRequest.model_validate({"Text": "a" * 5000})
        with patch("route.services.text_to_speech.shutil.which", return_value=None), \
             patch("route.services.text_to_speech.create_polly_client") as factory:
            with self.assertRaises(PollyUnavailableError):
                await generate_tts_mp3(payload, asyncio.Semaphore(1))
            factory.assert_not_called()

    async def test_partial_failure_never_encodes_or_returns_partial_audio(self):
        client = Mock()
        stream = io.BytesIO(b"\x00\x00")
        client.synthesize_speech.side_effect = [{"AudioStream": stream}, RuntimeError("failed")]
        with patch("route.services.text_to_speech.create_polly_client", return_value=client), \
             patch("route.services.text_to_speech.require_encoder", return_value="ffmpeg"), \
             patch("route.services.text_to_speech.encode_pcm_mp3") as encode:
            with self.assertRaises(RuntimeError):
                await generate_tts_mp3(TTSRequest.model_validate({"Text": "a" * 7000}), asyncio.Semaphore(1))
            encode.assert_not_called()
        self.assertTrue(stream.closed)
        client.close.assert_called_once()


class AudioEncodingTests(unittest.TestCase):
    def test_real_encoder_produces_decodable_audio_with_combined_duration(self):
        import imageio_ffmpeg
        encoder = imageio_ffmpeg.get_ffmpeg_exe()
        # Two different tones, each half a second; no AWS or network access.
        pcm = b"".join(struct.pack("<h", int(4000 * math.sin(2 * math.pi * hz * i / 16000)))
                       for hz in [440, 880] for i in range(8000))
        mp3 = encode_pcm_mp3(pcm, encoder)
        decoded = subprocess.run([encoder, "-loglevel", "error", "-i", "pipe:0", "-f", "s16le",
                                  "-ar", "16000", "-ac", "1", "pipe:1"], input=mp3,
                                 capture_output=True, timeout=20, check=True).stdout
        self.assertGreater(len(mp3), 0)
        self.assertAlmostEqual(1.0, len(decoded) / 32000, delta=0.15)
