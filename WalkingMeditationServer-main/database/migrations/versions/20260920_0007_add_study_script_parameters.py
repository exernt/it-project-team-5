"""add study script generation parameters

Revision ID: 20260920_0007
Revises: 20260914_0006
Create Date: 2026-09-20
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "20260920_0007"
down_revision: Union[str, Sequence[str], None] = "20260914_0006"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "study",
        sa.Column(
            "script_generation_parameters",
            sa.Text(),
            server_default=sa.text("''"),
            nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_column("study", "script_generation_parameters")
