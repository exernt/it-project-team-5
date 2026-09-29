"""Add repeatable questionnaire issuance rounds.

Revision ID: 20260924_0009
Revises: 4b4665bac746
Create Date: 2026-09-24
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "20260924_0009"
down_revision: Union[str, Sequence[str], None] = "4b4665bac746"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "study_questionnaires",
        sa.Column("round_number", sa.Integer(), nullable=True),
    )
    op.execute("UPDATE study_questionnaires SET round_number = 1")
    op.alter_column(
        "study_questionnaires",
        "round_number",
        existing_type=sa.Integer(),
        nullable=False,
    )

    op.drop_constraint(
        "uq_study_questionnaire",
        "study_questionnaires",
        type_="unique",
    )
    op.create_unique_constraint(
        "uq_study_questionnaire_round",
        "study_questionnaires",
        ["study_id", "questionnaire_id", "round_number"],
    )
    op.create_unique_constraint(
        "uq_questionnaire_response_respondent_round",
        "questionnaire_responses",
        ["study_questionnaire_id", "respondent_id"],
    )


def downgrade() -> None:
    op.drop_constraint(
        "uq_questionnaire_response_respondent_round",
        "questionnaire_responses",
        type_="unique",
    )
    op.drop_constraint(
        "uq_study_questionnaire_round",
        "study_questionnaires",
        type_="unique",
    )

    # Only one round can fit the old constraint. Keep the earliest round and
    # its responses if this migration is deliberately downgraded.
    op.execute(
        """
        DELETE FROM study_questionnaires newer
        USING study_questionnaires earlier
        WHERE newer.study_id = earlier.study_id
          AND newer.questionnaire_id = earlier.questionnaire_id
          AND newer.round_number > earlier.round_number
        """
    )
    op.create_unique_constraint(
        "uq_study_questionnaire",
        "study_questionnaires",
        ["study_id", "questionnaire_id"],
    )
    op.drop_column("study_questionnaires", "round_number")
