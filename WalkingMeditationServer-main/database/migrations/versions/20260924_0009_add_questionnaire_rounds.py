
"""Add repeatable questionnaire issuance rounds and repair legacy stub schema."""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import ENUM, JSONB

revision: str = "20260924_0009"
down_revision: Union[str, Sequence[str], None] = "4b4665bac746"
branch_labels = None
depends_on = None


def _repair_missing_questionnaire_schema() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    tables = set(inspector.get_table_names(schema="public"))

    # The normal schema is already present; nothing to repair.
    if "study_questionnaires" in tables:
        return

    # The legacy stub may be removed only if it contains no data.
    if "questionnaires" in tables:
        columns = {
            column["name"]
            for column in inspector.get_columns(
                "questionnaires", schema="public"
            )
        }

        if {"study_id", "id", "data"}.issubset(columns):
            count = bind.execute(
                sa.text("SELECT COUNT(*) FROM public.questionnaires")
            ).scalar_one()

            if count:
                raise RuntimeError(
                    "Legacy questionnaires table contains data; "
                    "refusing to drop it automatically."
                )

            op.drop_table("questionnaires")

    # Create the questionnaire status enum if it is missing.
    questionnaire_status = ENUM(
        "draft",
        "published",
        "archived",
        name="questionnaire_status",
        create_type=False,
    )
    questionnaire_status.create(bind, checkfirst=True)
    tables = set(sa.inspect(bind).get_table_names(schema="public"))

    if "questionnaires" not in tables:
        op.create_table(
            "questionnaires",
            sa.Column(
                "id", sa.Uuid(as_uuid=True),
                primary_key=True,
                server_default=sa.text("gen_random_uuid()"),
            ),
            sa.Column("title", sa.Text(), nullable=False),
            sa.Column(
                "researcher_id", sa.Uuid(as_uuid=True),
                sa.ForeignKey("users.id", ondelete="SET NULL"),
                nullable=True,
            ),
            sa.Column(
                "status", questionnaire_status,
                nullable=False,
                server_default=sa.text(
                    "'draft'::questionnaire_status"
                ),
            ),
            sa.Column("definition", JSONB(), nullable=False),
            sa.Column(
                "created_on", sa.DateTime(timezone=True),
                nullable=False, server_default=sa.func.now(),
            ),
        )
        op.create_index(
            "ix_questionnaires_researcher_id",
            "questionnaires", ["researcher_id"],
        )

    tables = set(sa.inspect(bind).get_table_names(schema="public"))

    if "study_questionnaires" not in tables:
        op.create_table(
            "study_questionnaires",
            sa.Column(
                "id", sa.Uuid(as_uuid=True),
                primary_key=True,
                server_default=sa.text("gen_random_uuid()"),
            ),
            sa.Column(
                "study_id", sa.Uuid(as_uuid=True),
                sa.ForeignKey("study.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column(
                "questionnaire_id", sa.Uuid(as_uuid=True),
                sa.ForeignKey("questionnaires.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column(
                "issued_at", sa.DateTime(timezone=True),
                nullable=False, server_default=sa.func.now(),
            ),
            sa.Column("revoked_at", sa.DateTime(timezone=True)),
            sa.UniqueConstraint(
                "study_id", "questionnaire_id",
                name="uq_study_questionnaire",
            ),
        )
        op.create_index(
            "ix_study_questionnaires_study_id",
            "study_questionnaires", ["study_id"],
        )
        op.create_index(
            "ix_study_questionnaires_questionnaire_id",
            "study_questionnaires", ["questionnaire_id"],
        )

    tables = set(sa.inspect(bind).get_table_names(schema="public"))

    if "questionnaire_responses" not in tables:
        op.create_table(
            "questionnaire_responses",
            sa.Column(
                "id", sa.Uuid(as_uuid=True),
                primary_key=True,
                server_default=sa.text("gen_random_uuid()"),
            ),
            sa.Column(
                "study_questionnaire_id", sa.Uuid(as_uuid=True),
                sa.ForeignKey(
                    "study_questionnaires.id", ondelete="CASCADE"
                ),
                nullable=False,
            ),
            sa.Column(
                "respondent_id", sa.Uuid(as_uuid=True),
                sa.ForeignKey("users.id", ondelete="SET NULL"),
                nullable=True,
            ),
            sa.Column("answers", JSONB(), nullable=False),
            sa.Column(
                "submitted_on", sa.DateTime(timezone=True),
                nullable=False, server_default=sa.func.now(),
            ),
        )
        op.create_index(
            "ix_questionnaire_responses_study_questionnaire_id",
            "questionnaire_responses", ["study_questionnaire_id"],
        )
        op.create_index(
            "ix_questionnaire_responses_respondent_id",
            "questionnaire_responses", ["respondent_id"],
        )


def upgrade() -> None:
    _repair_missing_questionnaire_schema()

    inspector = sa.inspect(op.get_bind())
    tables = set(inspector.get_table_names(schema="public"))

    if "study_questionnaires" not in tables:
        raise RuntimeError("study_questionnaires is still missing.")

    columns = {
        column["name"]
        for column in inspector.get_columns(
            "study_questionnaires", schema="public"
        )
    }

    if "round_number" not in columns:
        op.add_column(
            "study_questionnaires",
            sa.Column("round_number", sa.Integer(), nullable=True),
        )
        op.execute(
            "UPDATE study_questionnaires SET round_number = 1"
        )
        op.alter_column(
            "study_questionnaires",
            "round_number",
            existing_type=sa.Integer(),
            nullable=False,
        )

    inspector = sa.inspect(op.get_bind())
    unique_constraints = {
        constraint["name"]
        for constraint in inspector.get_unique_constraints(
            "study_questionnaires", schema="public"
        )
    }

    if "uq_study_questionnaire" in unique_constraints:
        op.drop_constraint(
            "uq_study_questionnaire",
            "study_questionnaires",
            type_="unique",
        )

    if "uq_study_questionnaire_round" not in unique_constraints:
        op.create_unique_constraint(
            "uq_study_questionnaire_round",
            "study_questionnaires",
            ["study_id", "questionnaire_id", "round_number"],
        )

    response_constraints = {
        constraint["name"]
        for constraint in sa.inspect(op.get_bind()).get_unique_constraints(
            "questionnaire_responses", schema="public"
        )
    }

    if "uq_questionnaire_response_respondent_round" not in response_constraints:
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
    op.create_unique_constraint(
        "uq_study_questionnaire",
        "study_questionnaires",
        ["study_id", "questionnaire_id"],
    )
    op.drop_column("study_questionnaires", "round_number")
