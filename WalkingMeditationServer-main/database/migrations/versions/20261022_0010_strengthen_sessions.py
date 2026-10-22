"""strengthen refresh sessions and access-token invalidation

Revision ID: 20261022_0010
Revises: 20260924_0009
Create Date: 2026-10-22

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "20261022_0010"
down_revision: Union[str, Sequence[str], None] = "20260924_0009"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "users",
        sa.Column("session_version", sa.Integer(), server_default=sa.text("0"), nullable=False),
    )

    op.add_column("refresh_tokens", sa.Column("family_id", sa.Uuid(), nullable=True))
    op.add_column("refresh_tokens", sa.Column("parent_id", sa.Uuid(), nullable=True))
    # Existing standalone tokens each become the root of their own family.
    op.execute("UPDATE refresh_tokens SET family_id = id")
    op.alter_column("refresh_tokens", "family_id", nullable=False)
    op.create_index(
        op.f("ix_refresh_tokens_family_id"),
        "refresh_tokens",
        ["family_id"],
        unique=False,
    )
    op.create_unique_constraint(
        "uq_refresh_tokens_parent_id",
        "refresh_tokens",
        ["parent_id"],
    )
    op.create_foreign_key(
        "fk_refresh_tokens_parent_id_refresh_tokens",
        "refresh_tokens",
        "refresh_tokens",
        ["parent_id"],
        ["id"],
        ondelete="SET NULL",
    )


def downgrade() -> None:
    op.drop_constraint(
        "fk_refresh_tokens_parent_id_refresh_tokens",
        "refresh_tokens",
        type_="foreignkey",
    )
    op.drop_constraint("uq_refresh_tokens_parent_id", "refresh_tokens", type_="unique")
    op.drop_index(op.f("ix_refresh_tokens_family_id"), table_name="refresh_tokens")
    op.drop_column("refresh_tokens", "parent_id")
    op.drop_column("refresh_tokens", "family_id")
    op.drop_column("users", "session_version")
