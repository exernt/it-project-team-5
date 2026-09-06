"""add role and researcher profiles

Revision ID: dc76bb64f211
Revises: dabace07270e
Create Date: 2026-09-06 16:47:53.500586

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = 'dc76bb64f211'
down_revision: Union[str, Sequence[str], None] = 'dabace07270e'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    user_role = postgresql.ENUM('participant', 'researcher', 'admin', name='user_role')
    user_role.create(op.get_bind(), checkfirst=True)

    op.create_table('researcher_profiles',
                    sa.Column('user_id', sa.Uuid(), nullable=False),
                    sa.Column('institution', sa.Text(), nullable=True),
                    sa.Column('verified_at', sa.DateTime(timezone=True), nullable=True),
                    sa.Column('created_on', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
                    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
                    sa.PrimaryKeyConstraint('user_id')
                    )
    op.add_column('users', sa.Column('role', user_role, server_default=sa.text("'participant'::user_role"), nullable=False))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('users', 'role')
    op.drop_table('researcher_profiles')
    postgresql.ENUM(name='user_role').drop(op.get_bind(), checkfirst=True)