"""create questionnaires and questionnaire_responses tables

Revision ID: 4a1f9c2e6b7d
Revises: 20260906_0003
Create Date: 2026-09-13 00:00:00.000000

"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB


revision: str = "4a1f9c2e6b7d"
down_revision: Union[str, Sequence[str], None] = "20260906_0003"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # No manual enum creation here: op.create_table() below will create
    # the questionnaire_status enum type automatically as a side effect
    # of creating this column, exactly once. (An earlier version of this
    # migration pre-created the type manually and then let create_table
    # create it again -- that self-inflicted duplication, not stale
    # database state, was the actual cause of the DuplicateObjectError
    # seen in earlier attempts.)
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
            nullable=False,
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
        "questionnaire_responses",
        sa.Column(
            "id",
            sa.Uuid(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "questionnaire_id",
            sa.Uuid(as_uuid=True),
            sa.ForeignKey("questionnaires.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "respondent_id",
            sa.Uuid(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
            nullable=False,
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
        "ix_questionnaire_responses_questionnaire_id",
        "questionnaire_responses",
        ["questionnaire_id"],
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
        "ix_questionnaire_responses_questionnaire_id",
        table_name="questionnaire_responses",
    )
    op.drop_table("questionnaire_responses")

    op.drop_index("ix_questionnaires_researcher_id", table_name="questionnaires")
    op.drop_table("questionnaires")

    sa.Enum(name="questionnaire_status").drop(op.get_bind(), checkfirst=True)