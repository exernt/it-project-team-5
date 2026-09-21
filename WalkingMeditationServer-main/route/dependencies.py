from __future__ import annotations

from uuid import UUID

from typing import Annotated

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from database.core import DatabaseSessionDependency
from database.models import ResearcherProfile, Role, Study, StudyCreatedBy, User
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


async def get_verified_researcher(
    user: CurrentUserDependency,
    session: DatabaseSessionDependency,
) -> User:
    """Require a researcher account that has been approved by an admin."""
    if user.role != Role.RESEARCHER:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only researchers can perform this action",
        )

    profile = await session.get(ResearcherProfile, user.id)
    if profile is None or profile.verified_at is None:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Researcher account has not been verified",
        )

    return user


VerifiedResearcherDependency = Annotated[User, Depends(get_verified_researcher)]

def require_role(*allowed_roles: Role):
    """FastAPI dependency factory: reject with 403 unless the current user's
    role is one of allowed_roles. Use as Depends(require_role(Role.ADMIN))."""

    async def _check_role(user: CurrentUserDependency) -> User:
        if user.role not in allowed_roles:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="You do not have permission to perform this action",
            )
        return user

    return _check_role

async def require_study_owner(
        study_id: UUID,
        researcher: VerifiedResearcherDependency,
        session: DatabaseSessionDependency,
) -> User:
    """Require that the current verified researcher owns the given study."""
    study = await session.get(Study, study_id)
    if study is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Study not found",
        )

    ownership = await session.get(StudyCreatedBy, study_id)
    if ownership is None or ownership.researcher_id != researcher.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You do not own this study",
        )

    return researcher


StudyOwnerDependency = Annotated[User, Depends(require_study_owner)]