from __future__ import annotations

from typing import Annotated

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from route.persistence.database import DatabaseSessionDependency
from route.persistence.orm_models import User
from route.services.tokens import decode_access_token

_bearer_scheme = HTTPBearer()


def _invalid_credentials() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid or expired access token",
        headers={"WWW-Authenticate": "Bearer"},
    )


async def get_current_user(
        credentials: Annotated[HTTPAuthorizationCredentials, Depends(_bearer_scheme)],
        session: DatabaseSessionDependency,
) -> User:
    """FastAPI dependency: require a valid access token, return the User it names."""
    try:
        user_id = decode_access_token(credentials.credentials)
    except jwt.PyJWTError:
        raise _invalid_credentials()

    user = await session.get(User, user_id)
    if user is None:
        raise _invalid_credentials()

    return user


CurrentUserDependency = Annotated[User, Depends(get_current_user)]