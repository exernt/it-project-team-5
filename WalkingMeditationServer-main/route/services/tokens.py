from __future__ import annotations

import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from uuid import UUID

import jwt

from route.config import (
    access_token_ttl_minutes,
    jwt_algorithm,
    jwt_secret_key,
    refresh_token_ttl_days,
)


def create_access_token(user_id: UUID) -> str:
    """Issue a short-lived, signed JWT identifying this user."""
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(user_id),
        "iat": now,
        "exp": now + timedelta(minutes=access_token_ttl_minutes()),
    }
    return jwt.encode(payload, jwt_secret_key(), algorithm=jwt_algorithm())


def decode_access_token(token: str) -> UUID:
    """Verify and decode an access token, returning the user id it identifies.

    Raises jwt.PyJWTError (or a subclass) if the token is invalid, expired,
    or has been tampered with.
    """
    payload = jwt.decode(token, jwt_secret_key(), algorithms=[jwt_algorithm()])
    return UUID(payload["sub"])


def generate_refresh_token() -> str:
    """A high-entropy, URL-safe token to hand to the client."""
    return secrets.token_urlsafe(32)


def hash_refresh_token(token: str) -> str:
    """Fast hash for storage/lookup — same reasoning as password reset tokens."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def refresh_token_expiry() -> datetime:
    return datetime.now(timezone.utc) + timedelta(days=refresh_token_ttl_days())