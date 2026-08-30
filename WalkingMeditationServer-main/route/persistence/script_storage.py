from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4


def save_script_text(script: str, output_folder: Path, *, prefix: str = "meditation_script") -> Path:
    output_folder.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S_%f")
    filename = f"{prefix}_{timestamp}_{uuid4().hex[:8]}.txt"
    file_path = output_folder / filename
    file_path.write_text(script, encoding="utf-8")
    return file_path
