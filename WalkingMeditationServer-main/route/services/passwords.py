import asyncio

from argon2 import PasswordHasher, Type


_password_hasher = PasswordHasher(type=Type.ID)


async def hash_password(password: str) -> str:
    """Hash a password without blocking FastAPI's event loop."""
    return await asyncio.to_thread(_password_hasher.hash, password)
