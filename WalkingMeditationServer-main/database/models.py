from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import Boolean, CheckConstraint, DateTime, Text, UniqueConstraint, Uuid, func, text, ForeignKey
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from database.core import Base

import enum

from sqlalchemy import Enum as SqlEnum

class Role(str, enum.Enum):
    PARTICIPANT = "participant"
    RESEARCHER = "researcher"
    ADMIN = "admin"

class User(Base):
    """Authentication identity stored by the server."""

    __tablename__ = "users"
    __table_args__ = (
        UniqueConstraint("email", name="uq_users_email"),
        CheckConstraint(
            "age IS NULL OR (age >= 0 AND age <= 150)",
            name="ck_users_age_range",
        ),
    )

    id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    email: Mapped[str] = mapped_column(Text, nullable=False)
    password_hash: Mapped[str] = mapped_column(Text, nullable=False)
    gender: Mapped[str | None] = mapped_column(Text, nullable=True)
    age: Mapped[int | None] = mapped_column(nullable=True)
    role: Mapped[Role] = mapped_column(
        SqlEnum(
            Role,
            name="user_role",
            native_enum=True,
            values_callable=lambda enum_cls: [member.value for member in enum_cls],
        ),
        nullable=False,
        server_default=text("'participant'::user_role"),
    )
    email_verified: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        server_default=text("false"),
    )
    created_on: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
class PasswordResetToken(Base):
    """A single-use, time-limited token for resetting a user's password."""

    __tablename__ = "password_reset_tokens"
    __table_args__ = (
        UniqueConstraint("token_hash", name="uq_password_reset_tokens_token_hash"),
    )

    id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    user_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    token_hash: Mapped[str] = mapped_column(Text, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_on: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )

class RefreshToken(Base):
    """A long-lived, revocable token used to mint new access tokens."""

    __tablename__ = "refresh_tokens"
    __table_args__ = (
        UniqueConstraint("token_hash", name="uq_refresh_tokens_token_hash"),
    )

    id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    user_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    token_hash: Mapped[str] = mapped_column(Text, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_on: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )

class ResearcherProfile(Base):
    """Extra profile data for users with the researcher role.

    verified_at is nullable and stays null until an admin approves the
    researcher — having role=researcher alone isn't enough to launch a
    study, per the design model's gating rule.
    """

    __tablename__ = "researcher_profiles"

    user_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        primary_key=True,
    )
    institution: Mapped[str | None] = mapped_column(Text, nullable=True)
    verified_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )
    created_on: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )

class StudyStatus(str, enum.Enum):
    DRAFT = "draft"
    RECRUITING = "recruiting"
    ACTIVE = "active"
    CLOSED = "closed"


class StudyGroup(str, enum.Enum):
    CONTROL = "control"
    EXPERIMENTAL = "experimental"


class Study(Base):
    """A researcher-created walking meditation study."""

    __tablename__ = "study"
    __table_args__ = (
        CheckConstraint("duration > 0", name="ck_study_duration_positive"),
    )

    id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    name: Mapped[str] = mapped_column(Text, nullable=False)
    duration: Mapped[int] = mapped_column(nullable=False)
    status: Mapped[StudyStatus] = mapped_column(
        SqlEnum(
            StudyStatus,
            name="study_status",
            native_enum=True,
            values_callable=lambda enum_cls: [member.value for member in enum_cls],
        ),
        nullable=False,
        server_default=text("'draft'::study_status"),
    )
class StudyRegistration(Base):
    """Connect a user to a study they have joined."""

    __tablename__ = "study_registrations"

    user_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        primary_key=True,
    )
    study_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("study.id", ondelete="CASCADE"),
        primary_key=True,
        index=True,
    )
    registered_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    consented_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
    )
    withdrawn_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )
    study_group: Mapped[StudyGroup] = mapped_column(
        SqlEnum(
            StudyGroup,
            name="study_group",
            native_enum=True,
            values_callable=lambda enum_cls: [member.value for member in enum_cls],
        ),
        nullable=False,
    )


class StudyCreatedBy(Base):
    """Record the researcher who created a study."""

    __tablename__ = "study_created_by"

    researcher_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    study_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("study.id", ondelete="CASCADE"),
        primary_key=True,
    )
    created_on: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )

class ConsentForm(Base):
    """Placeholder consent text shown to a participant before joining a study.

    Content is a placeholder until the client specifies what consent should
    actually cover — the mechanism (record that consent was given, when)
    is real; the wording is not final.
    """

    __tablename__ = "consent_form"

    study_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("study.id", ondelete="CASCADE"),
        primary_key=True,
    )
    body_text: Mapped[str] = mapped_column(Text, nullable=False)
    version: Mapped[int] = mapped_column(nullable=False, server_default=text("1"))


class Questionnaire(Base):
    """A JSON questionnaire belonging to a study.

    The payload remains intentionally unstructured while the questionnaire
    format is being designed. IDs are allocated sequentially within a study.
    """

    __tablename__ = "questionnaires"
    __table_args__ = (
        CheckConstraint("id > 0", name="ck_questionnaires_id_positive"),
    )

    study_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("study.id", ondelete="CASCADE"),
        primary_key=True,
    )
    id: Mapped[int] = mapped_column(primary_key=True)
    data: Mapped[dict[str, object]] = mapped_column(JSONB, nullable=False)
    created_on: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
