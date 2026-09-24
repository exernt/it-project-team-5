"""Add gender and age to users.

Revision ID: 20260914_0006
Revises: 20260914_0005
Create Date: 2026-09-14
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "20260914_0006"
down_revision: Union[str, Sequence[str], None] = "20260914_0005"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("users", sa.Column("gender", sa.Text(), nullable=True))
    op.add_column("users", sa.Column("age", sa.Integer(), nullable=True))
    op.create_check_constraint(
        "ck_users_age_range",
        "users",
        "age IS NULL OR (age >= 0 AND age <= 150)",
    )


def downgrade() -> None:
    op.drop_constraint("ck_users_age_range", "users", type_="check")
    op.drop_column("users", "age")
    op.drop_column("users", "gender")
