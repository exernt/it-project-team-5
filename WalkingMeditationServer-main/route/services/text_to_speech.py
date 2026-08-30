from __future__ import annotations

import asyncio
import tempfile
from pathlib import Path
from typing import Callable

import soundfile as sf
from fastapi import Response

from route.errors import TTSNotReadyError
from route.utils.utils import wav_to_mp3


async def generate_tts_mp3(
    model,
    semaphore: asyncio.Semaphore,
    text: str,
    language: str,
    speaker: str,
    instruct: str,
    bitrate: str = "192k",
    convert_wav_to_mp3: Callable[[Path, Path, str], None] = wav_to_mp3,
    offload_model_to_thread: bool = True,
) -> Response:
    if model is None:
        raise TTSNotReadyError("Model not loaded")

    def generate_and_convert() -> bytes:
        wavs, sr = model.generate_custom_voice(
            text=text,
            language=language,
            speaker=speaker,
            instruct=instruct,
        )

        if not wavs or wavs[0] is None:
            raise RuntimeError("TTS model returned no audio")

        with tempfile.TemporaryDirectory() as td:
            td_path = Path(td)
            wav_path = td_path / "out.wav"
            mp3_path = td_path / "out.mp3"

            sf.write(wav_path, wavs[0], sr)
            convert_wav_to_mp3(wav_path, mp3_path, bitrate)
            return mp3_path.read_bytes()

    async with semaphore:
        # Model inference, filesystem I/O, and ffmpeg are all blocking work.
        if offload_model_to_thread:
            mp3_bytes = await asyncio.to_thread(generate_and_convert)
        else:
            mp3_bytes = generate_and_convert()

    return Response(
        content=mp3_bytes,
        media_type="audio/mpeg",
        headers={"Content-Disposition": 'attachment; filename="speech.mp3"'},
    )
