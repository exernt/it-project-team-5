"""Open the newest locally captured password-reset email link in an emulator."""

from __future__ import annotations

import argparse
import re
import subprocess
from email import policy
from email.parser import BytesParser
from pathlib import Path


RESET_LINK_PATTERN = re.compile(
    r"walkingmeditation://password-reset#token=[A-Za-z0-9_-]{40,128}"
)


def _plain_text_body(message_path: Path) -> str:
    message = BytesParser(policy=policy.default).parsebytes(message_path.read_bytes())
    if message.is_multipart():
        for part in message.walk():
            if part.get_content_type() == "text/plain":
                return part.get_content()
    return message.get_content()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--capture-directory",
        type=Path,
        default=Path("var/dev-mail"),
    )
    parser.add_argument(
        "--package",
        default="com.example.walking_meditation",
    )
    args = parser.parse_args()

    messages = sorted(
        args.capture_directory.glob("password-reset-*.eml"),
        key=lambda path: path.stat().st_mtime,
    )
    if not messages:
        raise SystemExit(f"No captured reset email found in {args.capture_directory}")

    match = RESET_LINK_PATTERN.search(_plain_text_body(messages[-1]))
    if match is None:
        raise SystemExit("The newest captured email does not contain a local reset link")

    subprocess.run(
        [
            "adb",
            "shell",
            "am",
            "start",
            "-W",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            match.group(0),
            args.package,
        ],
        check=True,
    )
    print("Opened the newest captured password-reset email in the emulator.")


if __name__ == "__main__":
    main()
