"""add study status

Revision ID: 0dc5a08dba58
Revises: 20260906_0003
Create Date: 2026-09-13 16:52:24.630864

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = '0dc5a08dba58'
down_revision: Union[str, Sequence[str], None] = '20260906_0003'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    study_status = postgresql.ENUM('draft', 'recruiting', 'active', 'closed', name='study_status')
    study_status.create(op.get_bind(), checkfirst=True)

    op.add_column('study', sa.Column('status', study_status, server_default=sa.text("'draft'::study_status"), nullable=False))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('study', 'status')
    postgresql.ENUM(name='study_status').drop(op.get_bind(), checkfirst=True)