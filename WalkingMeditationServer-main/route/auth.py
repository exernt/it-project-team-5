from fastapi import APIRouter, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from route.models import RegisterRequest, RegisterResponse
from route.persistence.database import DatabaseSessionDependency
from route.persistence.orm_models import User
from route.services.passwords import hash_password


router = APIRouter(prefix="/auth", tags=["authentication"])


def _email_already_registered() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail="Email is already registered",
    )


@router.post(
    "/register",
    response_model=RegisterResponse,
    status_code=status.HTTP_201_CREATED,
)
async def register_user(
    payload: RegisterRequest,
    session: DatabaseSessionDependency,
) -> RegisterResponse:
    email = str(payload.email)
    existing_user_id = await session.scalar(
        select(User.id).where(User.email == email)
    )
    if existing_user_id is not None:
        raise _email_already_registered()

    user = User(
        email=email,
        password_hash=await hash_password(payload.password),
    )
    session.add(user)

    try:
        await session.commit()
    except IntegrityError as exc:
        # The unique constraint handles two registrations racing after the
        # existence check. Roll back before translating it to an API response.
        await session.rollback()
        raise _email_already_registered() from exc

    return RegisterResponse(message="Registration successful")
