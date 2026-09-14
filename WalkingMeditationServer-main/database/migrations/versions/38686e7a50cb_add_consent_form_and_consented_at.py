"""add consent form and consented_at

Revision ID: 38686e7a50cb
Revises: 0dc5a08dba58
Create Date: 2026-09-14 12:29:46.345713

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = '38686e7a50cb'
down_revision: Union[str, Sequence[str], None] = '0dc5a08dba58'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table('consent_form',
                    sa.Column('study_id', sa.Uuid(), nullable=False),
                    sa.Column('body_text', sa.Text(), nullable=False),
                    sa.Column('version', sa.Integer(), server_default=sa.text('1'), nullable=False),
                    sa.ForeignKeyConstraint(['study_id'], ['study.id'], ondelete='CASCADE'),
                    sa.PrimaryKeyConstraint('study_id')
                    )

    # Backfill existing registrations with a default so the NOT NULL
    # constraint can be added; these are pre-existing test rows without a
    # real consent timestamp, so 'now()' at migration time is a placeholder.
    op.add_column(
        'study_registrations',
        sa.Column(
            'consented_at',
            sa.DateTime(timezone=True),
            server_default=sa.text('now()'),
            nullable=False,
        ),
    )
    # Drop the default afterward — going forward, every new registration
    # must explicitly provide consented_at, matching the ORM model.
    op.alter_column('study_registrations', 'consented_at', server_default=None)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('study_registrations', 'consented_at')
    op.drop_table('consent_form')