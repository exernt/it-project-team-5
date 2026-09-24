"""create study table

Revision ID: 20260906_0002
Revises: dc76bb64f211
Create Date: 2026-09-06

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "20260906_0002"
down_revision: Union[str, Sequence[str], None] = "dc76bb64f211"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "study",
        sa.Column(
            "id",
            sa.Uuid(),
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("duration", sa.Integer(), nullable=False),
        sa.CheckConstraint("duration > 0", name="ck_study_duration_positive"),
        sa.PrimaryKeyConstraint("id"),
    )


def downgrade() -> None:
    op.drop_table("study")
