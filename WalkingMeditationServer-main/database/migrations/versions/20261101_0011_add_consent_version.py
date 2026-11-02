"""record the consent form version on each study registration

Revision ID: 20261101_0011
Revises: 20261022_0010
Create Date: 2026-11-01 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "20261101_0011"
down_revision: Union[str, Sequence[str], None] = "20261022_0010"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Every existing registration agreed to the original placeholder text,
    # which is version 1, so 1 is the correct backfill rather than a guess.
    op.add_column(
        "study_registrations",
        sa.Column(
            "consent_version",
            sa.Integer(),
            server_default=sa.text("1"),
            nullable=False,
        ),
    )
    # Going forward every registration must state the version explicitly,
    # matching the ORM model.
    op.alter_column("study_registrations", "consent_version", server_default=None)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("study_registrations", "consent_version")
