from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import Boolean, CheckConstraint, DateTime, Text, UniqueConstraint, Uuid, func, text, ForeignKey
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.dialects.postgresql import JSONB

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
    description: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        default="",
        server_default=text("''"),
    )
    script_generation_parameters: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        default="",
        server_default=text("''"),
    )
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

class FeedbackCategory(str, enum.Enum):
    GENERAL = "general"
    STUDY = "study"
    TECHNICAL = "technical"


class Feedback(Base):
    """Participant-submitted feedback, optionally tied to a study."""

    __tablename__ = "feedback"
    __table_args__ = (
        CheckConstraint(
            "rating IS NULL OR (rating >= 1 AND rating <= 5)",
            name="ck_feedback_rating_range",
        ),
        CheckConstraint(
            "rating IS NOT NULL OR comment IS NOT NULL",
            name="ck_feedback_not_empty",
        ),
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
    study_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("study.id", ondelete="CASCADE"),
        nullable=True,
        index=True,
    )
    category: Mapped[FeedbackCategory] = mapped_column(
        SqlEnum(
            FeedbackCategory,
            name="feedback_category",
            native_enum=True,
            values_callable=lambda enum_cls: [member.value for member in enum_cls],
        ),
        nullable=False,
    )
    rating: Mapped[int | None] = mapped_column(nullable=True)
    comment: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
class QuestionnaireStatus(str, enum.Enum):
    DRAFT = "draft"
    PUBLISHED = "published"
    ARCHIVED = "archived"
 
 
class Questionnaire(Base):
    """A questionnaire definition, owned by a researcher, independent of
    any study it may later be issued to. UNCHANGED from the prior model."""
 
    __tablename__ = "questionnaires"
 
    id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    title: Mapped[str] = mapped_column(Text, nullable=False)
    researcher_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
        index=True,
    )
    status: Mapped[QuestionnaireStatus] = mapped_column(
        SqlEnum(
            QuestionnaireStatus,
            name="questionnaire_status",
            native_enum=True,
            values_callable=lambda enum_cls: [member.value for member in enum_cls],
        ),
        nullable=False,
        server_default=text("'draft'::questionnaire_status"),
    )
    definition: Mapped[dict] = mapped_column(JSONB, nullable=False)
    created_on: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )

class StudyQuestionnaire(Base):
    """Records that a researcher has issued a questionnaire to a study.
 
    Soft-deletable: revoking sets revoked_at rather than deleting the row,
    so existing responses stay linked to real history. Re-issuing an
    already-issued pair (whether currently active or previously revoked)
    reactivates this same row -- clears revoked_at, refreshes issued_at --
    rather than inserting a duplicate. The UniqueConstraint below is what
    makes "the same row" well-defined; route logic must query for an
    existing row on this (study_id, questionnaire_id) pair before deciding
    whether to insert or update.
    """
 
    __tablename__ = "study_questionnaires"
    __table_args__ = (
        UniqueConstraint("study_id", "questionnaire_id", name="uq_study_questionnaire"),
    )
 
    id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    study_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("study.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    questionnaire_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("questionnaires.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    issued_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    revoked_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )


class QuestionnaireResponse(Base):
    """A single respondent's submitted answers, scoped to a specific
    study's issuance of a questionnaire (not the questionnaire directly --
    see StudyQuestionnaire). study_id and questionnaire_id are intentionally
    NOT stored here; both are derived by joining through
    study_questionnaire_id, so a response can't exist for a pairing that
    was never actually issued.
    """
 
    __tablename__ = "questionnaire_responses"
 
    id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    study_questionnaire_id: Mapped[UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("study_questionnaires.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    respondent_id: Mapped[UUID | None] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
        index=True,
    )
    answers: Mapped[dict] = mapped_column(JSONB, nullable=False)
    submitted_on: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )