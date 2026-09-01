from __future__ import annotations

import hashlib
import secrets
from datetime import datetime, timedelta, timezone

RESET_TOKEN_TTL_MINUTES = 30


def generate_reset_token() -> str:
    """A high-entropy, URL-safe token to email to the user."""
    return secrets.token_urlsafe(32)


def hash_reset_token(token: str) -> str:
    """Fast hash for storage/lookup. Unlike passwords, this token is already
    high-entropy, so a slow KDF like argon2 isn't needed here."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def reset_token_expiry() -> datetime:
    return datetime.now(timezone.utc) + timedelta(minutes=RESET_TOKEN_TTL_MINUTES)