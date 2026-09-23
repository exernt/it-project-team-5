from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.sql import func

from route.models import (
    CurrentUserResponse,
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
    RefreshRequest,
    RefreshResponse,
    RegisterResearcherRequest,
    ResearcherProfileResponse,
)
from database.core import DatabaseSessionDependency
from database.models import User, PasswordResetToken, RefreshToken, ResearcherProfile, Role
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
from route.dependencies import CurrentUserDependency, require_role
from uuid import UUID

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
        gender=payload.gender,
        age=payload.age,
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

def _invalid_refresh_token() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Refresh token is invalid, expired, or has been revoked",
    )


@router.post(
    "/refresh",
    response_model=RefreshResponse,
)
async def refresh(
        payload: RefreshRequest,
        session: DatabaseSessionDependency,
) -> RefreshResponse:
    token_hash = hash_refresh_token(payload.refresh_token)
    refresh_token_row = await session.scalar(
        select(RefreshToken).where(RefreshToken.token_hash == token_hash)
    )

    if (
            refresh_token_row is None
            or refresh_token_row.revoked_at is not None
            or refresh_token_row.expires_at < datetime.now(timezone.utc)
    ):
        raise _invalid_refresh_token()

    # Rotate: revoke the token that was just used, issue a brand new one.
    # If a stolen refresh token is ever used by an attacker, the legitimate
    # client's next refresh attempt will fail here — a detectable signal,
    # rather than the same token silently working for either party for 30 days.
    refresh_token_row.revoked_at = func.now()

    new_access_token = create_access_token(refresh_token_row.user_id)
    new_raw_refresh_token = generate_refresh_token()
    session.add(
        RefreshToken(
            user_id=refresh_token_row.user_id,
            token_hash=hash_refresh_token(new_raw_refresh_token),
            expires_at=refresh_token_expiry(),
        )
    )
    await session.commit()

    return RefreshResponse(
        access_token=new_access_token,
        refresh_token=new_raw_refresh_token,
    )

@router.post(
    "/register-researcher",
    response_model=ResearcherProfileResponse,
    status_code=status.HTTP_201_CREATED,
)
async def register_researcher(
        payload: RegisterResearcherRequest,
        user: CurrentUserDependency,
        session: DatabaseSessionDependency,
) -> ResearcherProfileResponse:
    existing_profile = await session.get(ResearcherProfile, user.id)
    if existing_profile is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Researcher profile already exists for this account",
        )

    user.role = Role.RESEARCHER
    profile = ResearcherProfile(
        user_id=user.id,
        institution=payload.institution,
    )
    session.add(profile)
    await session.commit()

    return ResearcherProfileResponse(
        institution=profile.institution,
        verified_at=profile.verified_at,
        role=user.role.value,
    )

@router.post(
    "/admin/researchers/{user_id}/verify",
    response_model=ResearcherProfileResponse,
    dependencies=[Depends(require_role(Role.ADMIN))],
)
async def verify_researcher(
        user_id: UUID,
        session: DatabaseSessionDependency,
) -> ResearcherProfileResponse:
    profile = await session.get(ResearcherProfile, user_id)
    if profile is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No researcher profile found for that user",
        )

    profile.verified_at = datetime.now(timezone.utc)
    await session.commit()

    user = await session.get(User, user_id)

    return ResearcherProfileResponse(
        institution=profile.institution,
        verified_at=profile.verified_at,
        role=user.role.value,
    )

@router.get(
    "/me",
    response_model=CurrentUserResponse,
)
async def get_current_user_info(
        user: CurrentUserDependency,
        session: DatabaseSessionDependency,
) -> CurrentUserResponse:
    institution = None
    verified_at = None

    if user.role == Role.RESEARCHER:
        profile = await session.get(ResearcherProfile, user.id)
        if profile is not None:
            institution = profile.institution
            verified_at = profile.verified_at

    return CurrentUserResponse(
        user_id=user.id,
        email=user.email,
        role=user.role.value,
        institution=institution,
        verified_at=verified_at,
    )