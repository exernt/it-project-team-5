"""Add control and experimental study groups.

Revision ID: 20260914_0005
Revises: 20260914_0004
Create Date: 2026-09-14
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = "20260914_0005"
down_revision: Union[str, Sequence[str], None] = "20260914_0004"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    study_group = postgresql.ENUM(
        "control",
        "experimental",
        name="study_group",
    )
    study_group.create(op.get_bind(), checkfirst=True)

    op.add_column(
        "study_registrations",
        sa.Column("study_group", study_group, nullable=True),
    )

    # Preserve existing registrations by assigning them alternately within
    # each study. Stable tie-breaking makes the earliest participant control.
    op.execute(
        """
        WITH ranked_registrations AS (
            SELECT
                user_id,
                study_id,
                ROW_NUMBER() OVER (
                    PARTITION BY study_id
                    ORDER BY registered_at, user_id
                ) AS registration_number
            FROM study_registrations
        )
        UPDATE study_registrations AS registration
        SET study_group = CASE
            WHEN ranked.registration_number % 2 = 1
                THEN 'control'::study_group
            ELSE 'experimental'::study_group
        END
        FROM ranked_registrations AS ranked
        WHERE registration.user_id = ranked.user_id
          AND registration.study_id = ranked.study_id
        """
    )

    op.alter_column(
        "study_registrations",
        "study_group",
        existing_type=study_group,
        nullable=False,
    )


def downgrade() -> None:
    op.drop_column("study_registrations", "study_group")
    postgresql.ENUM(name="study_group").drop(op.get_bind(), checkfirst=True)
