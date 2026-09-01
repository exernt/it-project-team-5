from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.sql import func

from route.models import (
    RegisterRequest,
    RegisterResponse,
    PasswordResetRequest,
    PasswordResetRequestResponse,
    PasswordResetConfirmRequest,
    PasswordResetConfirmResponse,
    LoginRequest,
    LoginResponse,
    LogoutRequest,
    LogoutResponse,
)
from route.persistence.database import DatabaseSessionDependency
from route.persistence.orm_models import User, PasswordResetToken, RefreshToken
from route.services.passwords import hash_password, verify_password
from route.services.password_reset import (
    generate_reset_token,
    hash_reset_token,
    reset_token_expiry,
)
from route.services.email_sender import send_password_reset_email
from route.services.tokens import (
    create_access_token,
    generate_refresh_token,
    hash_refresh_token,
    refresh_token_expiry,
)

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


def _invalid_reset_token() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_400_BAD_REQUEST,
        detail="Reset token is invalid or has expired",
    )


@router.post(
    "/password-reset/request",
    response_model=PasswordResetRequestResponse,
    status_code=status.HTTP_200_OK,
)
async def request_password_reset(
        payload: PasswordResetRequest,
        session: DatabaseSessionDependency,
) -> PasswordResetRequestResponse:
    email = str(payload.email)
    user_id = await session.scalar(select(User.id).where(User.email == email))

    if user_id is not None:
        # Invalidate any outstanding tokens so only the newest link works.
        await session.execute(
            update(PasswordResetToken)
            .where(
                PasswordResetToken.user_id == user_id,
                PasswordResetToken.used_at.is_(None),
                )
            .values(used_at=func.now())
        )

        raw_token = generate_reset_token()
        session.add(
            PasswordResetToken(
                user_id=user_id,
                token_hash=hash_reset_token(raw_token),
                expires_at=reset_token_expiry(),
            )
        )
        await session.commit()
        await send_password_reset_email(email, raw_token)

    # Always 200, whether or not the email is registered — otherwise this
    # endpoint could be used to enumerate which emails have accounts.
    return PasswordResetRequestResponse(
        message="If that email is registered, a reset link has been sent."
    )


@router.post(
    "/password-reset/confirm",
    response_model=PasswordResetConfirmResponse,
)
async def confirm_password_reset(
        payload: PasswordResetConfirmRequest,
        session: DatabaseSessionDependency,
) -> PasswordResetConfirmResponse:
    token_hash = hash_reset_token(payload.token)
    reset_token = await session.scalar(
        select(PasswordResetToken).where(PasswordResetToken.token_hash == token_hash)
    )

    if (
            reset_token is None
            or reset_token.used_at is not None
            or reset_token.expires_at < datetime.now(timezone.utc)
    ):
        raise _invalid_reset_token()

    user = await session.get(User, reset_token.user_id)
    if user is None:
        raise _invalid_reset_token()

    user.password_hash = await hash_password(payload.new_password)
    reset_token.used_at = func.now()
    await session.commit()

    return PasswordResetConfirmResponse(message="Password has been reset")


def _invalid_credentials() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid email or password",
    )


@router.post(
    "/login",
    response_model=LoginResponse,
)
async def login(
        payload: LoginRequest,
        session: DatabaseSessionDependency,
) -> LoginResponse:
    email = str(payload.email)
    user = await session.scalar(select(User).where(User.email == email))

    if user is None:
        raise _invalid_credentials()

    if not await verify_password(payload.password, user.password_hash):
        raise _invalid_credentials()

    access_token = create_access_token(user.id)
    raw_refresh_token = generate_refresh_token()
    session.add(
        RefreshToken(
            user_id=user.id,
            token_hash=hash_refresh_token(raw_refresh_token),
            expires_at=refresh_token_expiry(),
        )
    )
    await session.commit()

    return LoginResponse(
        access_token=access_token,
        refresh_token=raw_refresh_token,
    )


@router.post(
    "/logout",
    response_model=LogoutResponse,
)
async def logout(
        payload: LogoutRequest,
        session: DatabaseSessionDependency,
) -> LogoutResponse:
    token_hash = hash_refresh_token(payload.refresh_token)
    await session.execute(
        update(RefreshToken)
        .where(
            RefreshToken.token_hash == token_hash,
            RefreshToken.revoked_at.is_(None),
            )
        .values(revoked_at=func.now())
    )
    await session.commit()

    # Same message whether or not the token existed/was already revoked —
    # logout is idempotent from the client's point of view.
    return LogoutResponse(message="Logged out")