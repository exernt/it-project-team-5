import asyncio

from argon2 import PasswordHasher, Type
from argon2.exceptions import VerifyMismatchError


_password_hasher = PasswordHasher(type=Type.ID)


async def hash_password(password: str) -> str:
    """Hash a password without blocking FastAPI's event loop."""
    return await asyncio.to_thread(_password_hasher.hash, password)


async def verify_password(password: str, password_hash: str) -> bool:
    """Verify a password against its hash without blocking the event loop."""
    try:
        return await asyncio.to_thread(
            _password_hasher.verify, password_hash, password
        )
    except VerifyMismatchError:
        return False