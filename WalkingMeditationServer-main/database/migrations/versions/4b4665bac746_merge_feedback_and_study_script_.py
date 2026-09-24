"""merge feedback and study script parameter branches

Revision ID: 4b4665bac746
Revises: 6860ef0332f4, 20260920_0008
Create Date: 2026-09-21 00:25:58.688995

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa



revision: str = '4b4665bac746'
down_revision: Union[str, Sequence[str], None] = ('6860ef0332f4', '20260920_0008')
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    pass


def downgrade() -> None:
    """Downgrade schema."""
    pass
