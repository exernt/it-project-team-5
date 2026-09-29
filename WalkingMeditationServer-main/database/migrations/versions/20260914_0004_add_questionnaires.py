"""Add questionnaires.

Revision ID: 20260914_0004
Revises: 5252d5bf5e00
Create Date: 2026-09-14

Creates the full questionnaire schema in one step: a researcher-owned
`questionnaires` table (independent of any study), `study_questionnaires`
as the issuance/soft-delete join table linking a questionnaire to a
study, and `questionnaire_responses` scoped to a specific issuance rather
than directly to a questionnaire. study_id and questionnaire_id are
intentionally NOT stored on questionnaire_responses -- both are derived
by joining through study_questionnaire_id, so a response can never
reference a (study, questionnaire) pair that was never actually issued.

(This replaces an earlier draft of this migration that created a single
placeholder table -- a bare per-study JSONB blob keyed by (study_id, id)
-- before the full questionnaire design was settled. Since nothing has
run this migration yet, it's rewritten in place rather than layered with
a follow-up correction.)
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB


revision: str = "20260914_0004"
down_revision: Union[str, Sequence[str], None] = "5252d5bf5e00"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # No manual enum pre-creation -- create_table below creates
    # questionnaire_status automatically, exactly once.
    questionnaire_status = sa.Enum(
        "draft", "published", "archived",
        name="questionnaire_status",
    )

    op.create_table(
        "questionnaires",
        sa.Column(
            "id",
            sa.Uuid(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column("title", sa.Text(), nullable=False),
        sa.Column(
            "researcher_id",
            sa.Uuid(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column(
            "status",
            questionnaire_status,
            nullable=False,
            server_default=sa.text("'draft'::questionnaire_status"),
        ),
        sa.Column("definition", JSONB(), nullable=False),
        sa.Column(
            "created_on",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
    )
    op.create_index(
        "ix_questionnaires_researcher_id", "questionnaires", ["researcher_id"]
    )

    op.create_table(
        "study_questionnaires",
        sa.Column(
            "id",
            sa.Uuid(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "study_id",
            sa.Uuid(as_uuid=True),
            sa.ForeignKey("study.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "questionnaire_id",
            sa.Uuid(as_uuid=True),
            sa.ForeignKey("questionnaires.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "issued_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.UniqueConstraint("study_id", "questionnaire_id", name="uq_study_questionnaire"),
    )
    op.create_index(
        "ix_study_questionnaires_study_id", "study_questionnaires", ["study_id"]
    )
    op.create_index(
        "ix_study_questionnaires_questionnaire_id",
        "study_questionnaires",
        ["questionnaire_id"],
    )

    op.create_table(
        "questionnaire_responses",
        sa.Column(
            "id",
            sa.Uuid(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "study_questionnaire_id",
            sa.Uuid(as_uuid=True),
            sa.ForeignKey("study_questionnaires.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "respondent_id",
            sa.Uuid(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("answers", JSONB(), nullable=False),
        sa.Column(
            "submitted_on",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
    )
    op.create_index(
        "ix_questionnaire_responses_study_questionnaire_id",
        "questionnaire_responses",
        ["study_questionnaire_id"],
    )
    op.create_index(
        "ix_questionnaire_responses_respondent_id",
        "questionnaire_responses",
        ["respondent_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_questionnaire_responses_respondent_id",
        table_name="questionnaire_responses",
    )
    op.drop_index(
        "ix_questionnaire_responses_study_questionnaire_id",
        table_name="questionnaire_responses",
    )
    op.drop_table("questionnaire_responses")

    op.drop_index(
        "ix_study_questionnaires_questionnaire_id", table_name="study_questionnaires"
    )
    op.drop_index("ix_study_questionnaires_study_id", table_name="study_questionnaires")
    op.drop_table("study_questionnaires")

    op.drop_index("ix_questionnaires_researcher_id", table_name="questionnaires")
    op.drop_table("questionnaires")
    sa.Enum(name="questionnaire_status").drop(op.get_bind(), checkfirst=True)