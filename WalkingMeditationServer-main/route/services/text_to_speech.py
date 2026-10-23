from __future__ import annotations

import asyncio
import os
import shutil
import subprocess
from contextlib import closing

from fastapi import Response
from route.models import TTSRequest
from route.services.speech_chunks import split_speech

MAX_SYNTHESIS_CHUNKS = 10

class PollyRequestError(RuntimeError):
    """The supplied speech options cannot be synthesized."""


class PollyUnavailableError(RuntimeError):
    """AWS credentials, configuration, or service availability prevented synthesis."""


def create_polly_client():
    # Lazy initialization keeps AWS credential discovery off the startup path.
    import boto3
    from botocore.config import Config
    return boto3.client(
        "polly",
        region_name=os.getenv("AWS_REGION") or os.getenv("AWS_DEFAULT_REGION"),
        config=Config(connect_timeout=10, read_timeout=120,
                      retries={"mode": "standard", "total_max_attempts": 3}),
    )


def _synthesize(payload: TTSRequest) -> bytes:
    from botocore.exceptions import BotoCoreError, ClientError
    try:
        chunks = split_speech(payload.text, payload.text_type)
    except (ValueError, RecursionError) as exc:
        raise PollyRequestError("Cannot split this narration") from exc
    if len(chunks) > MAX_SYNTHESIS_CHUNKS:
        raise PollyRequestError("Narration requires too many synthesis chunks")
    encoder = require_encoder() if len(chunks) > 1 else None
    try:
        with closing(create_polly_client()) as client:
            audio_parts = []
            for chunk in chunks:
                request = payload.model_dump(by_alias=True, exclude_none=True)
                request["Text"] = chunk
                if encoder:
                    request.update(OutputFormat="pcm", SampleRate="16000")
                result = client.synthesize_speech(**request)
                stream = result.get("AudioStream")
                if stream is None:
                    raise RuntimeError("Polly returned no audio stream")
                with closing(stream):
                    audio = stream.read()
                if not audio:
                    raise RuntimeError("Polly returned empty audio")
                if encoder and len(audio) % 2:
                    raise RuntimeError("Polly returned incomplete PCM samples")
                audio_parts.append(audio)
            return encode_pcm_mp3(b"".join(audio_parts), encoder) if encoder else audio_parts[0]
    except ClientError as exc:
        code = exc.response.get("Error", {}).get("Code", "")
        if code in {"InvalidSsmlException", "TextLengthExceededException",
                    "EngineNotSupportedException", "LanguageNotSupportedException",
                    "InvalidParameterValueException", "InvalidSampleRateException"}:
            raise PollyRequestError("Invalid speech request") from exc
        raise PollyUnavailableError("Polly request failed") from exc
    except BotoCoreError as exc:
        raise PollyUnavailableError("Polly configuration or connection failed") from exc


def require_encoder() -> str:
    encoder = shutil.which(os.getenv("FFMPEG_BINARY", "ffmpeg"))
    if not encoder:
        raise PollyUnavailableError("ffmpeg is required for oversized narration")
    return encoder


def encode_pcm_mp3(pcm: bytes, encoder: str) -> bytes:
    # Encode the joined 16-bit mono PCM once. No MP3 byte concatenation or temporary files.
    result = subprocess.run(
        [encoder, "-hide_banner", "-loglevel", "error", "-f", "s16le", "-ar", "16000",
         "-ac", "1", "-i", "pipe:0", "-codec:a", "libmp3lame", "-b:a", "192k",
         "-f", "mp3", "pipe:1"], input=pcm, capture_output=True, timeout=120,
    )
    if result.returncode or not result.stdout:
        raise RuntimeError("Cannot encode joined narration")
    return result.stdout


async def generate_tts_mp3(payload: TTSRequest, semaphore: asyncio.Semaphore) -> Response:
    async with semaphore:
        # boto3 and AudioStream.read are blocking; keep both off the event loop.
        audio = await asyncio.to_thread(_synthesize, payload)
    return Response(
        content=audio, media_type="audio/mpeg",
        headers={"Content-Disposition": 'attachment; filename="speech.mp3"'},
    )
