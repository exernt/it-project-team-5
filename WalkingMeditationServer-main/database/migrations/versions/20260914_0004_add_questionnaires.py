"""Add questionnaires.

Revision ID: 20260914_0004
Revises: 5252d5bf5e00
Create Date: 2026-09-14
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = "20260914_0004"
down_revision: Union[str, Sequence[str], None] = "5252d5bf5e00"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "questionnaires",
        sa.Column("study_id", sa.Uuid(), nullable=False),
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("data", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column(
            "created_on",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint("id > 0", name="ck_questionnaires_id_positive"),
        sa.ForeignKeyConstraint(
            ["study_id"],
            ["study.id"],
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("study_id", "id"),
    )


def downgrade() -> None:
    op.drop_table("questionnaires")
