from __future__ import annotations

import asyncio
import os
from contextlib import closing

from fastapi import Response
from route.models import TTSRequest


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
        with closing(create_polly_client()) as client:
            result = client.synthesize_speech(**payload.model_dump(by_alias=True, exclude_none=True))
            stream = result.get("AudioStream")
            if stream is None:
                raise RuntimeError("Polly returned no audio stream")
            with closing(stream):
                audio = stream.read()
            if not audio:
                raise RuntimeError("Polly returned empty audio")
            return audio
    except ClientError as exc:
        code = exc.response.get("Error", {}).get("Code", "")
        if code in {"InvalidSsmlException", "TextLengthExceededException",
                    "EngineNotSupportedException", "LanguageNotSupportedException",
                    "InvalidParameterValueException", "InvalidSampleRateException"}:
            raise PollyRequestError("Invalid speech request") from exc
        raise PollyUnavailableError("Polly request failed") from exc
    except BotoCoreError as exc:
        raise PollyUnavailableError("Polly configuration or connection failed") from exc


async def generate_tts_mp3(payload: TTSRequest, semaphore: asyncio.Semaphore) -> Response:
    async with semaphore:
        # boto3 and AudioStream.read are blocking; keep both off the event loop.
        audio = await asyncio.to_thread(_synthesize, payload)
    return Response(
        content=audio, media_type="audio/mpeg",
        headers={"Content-Disposition": 'attachment; filename="speech.mp3"'},
    )
