"""create study registration and ownership tables

Revision ID: 20260906_0003
Revises: 20260906_0002
Create Date: 2026-09-06

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "20260906_0003"
down_revision: Union[str, Sequence[str], None] = "20260906_0002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "study_registrations",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("study_id", sa.Uuid(), nullable=False),
        sa.Column(
            "registered_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["study_id"], ["study.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("user_id", "study_id"),
    )
    op.create_index(
        "ix_study_registrations_study_id",
        "study_registrations",
        ["study_id"],
    )

    op.create_table(
        "study_created_by",
        sa.Column("researcher_id", sa.Uuid(), nullable=False),
        sa.Column("study_id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_on",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["researcher_id"], ["users.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["study_id"], ["study.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("study_id"),
    )
    op.create_index(
        "ix_study_created_by_researcher_id",
        "study_created_by",
        ["researcher_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_study_created_by_researcher_id",
        table_name="study_created_by",
    )
    op.drop_table("study_created_by")
    op.drop_index(
        "ix_study_registrations_study_id",
        table_name="study_registrations",
    )
    op.drop_table("study_registrations")
