"""initial schema

Revision ID: 0001_initial_schema
Revises:
Create Date: 2026-04-15
"""

from alembic import op
import sqlalchemy as sa

revision = "0001_initial_schema"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("username", sa.String(length=80), nullable=False, unique=True),
        sa.Column("hashed_password", sa.String(length=255), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_users_username", "users", ["username"], unique=True)

    op.create_table(
        "sender_profiles",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("sender_id", sa.String(length=255), nullable=False, unique=True),
        sa.Column("scam_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("safe_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("risk_score", sa.Float(), nullable=False, server_default="0.5"),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
    )

    op.create_table(
        "detection_history",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("message_id", sa.String(length=120), nullable=False, unique=True),
        sa.Column("sender_id", sa.String(length=255), nullable=True),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("category", sa.String(length=100), nullable=False),
        sa.Column("confidence", sa.Float(), nullable=False),
        sa.Column("risk_level", sa.String(length=20), nullable=False),
        sa.Column("is_scam", sa.Boolean(), nullable=False),
        sa.Column("reasoning", sa.Text(), nullable=False),
        sa.Column("explanation", sa.Text(), nullable=False),
        sa.Column("detection_mode", sa.String(length=32), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )

    op.create_table(
        "baiting_sessions",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("session_id", sa.String(length=120), nullable=False, unique=True),
        sa.Column("sender_id", sa.String(length=255), nullable=False),
        sa.Column("persona", sa.String(length=120), nullable=False),
        sa.Column("goal", sa.String(length=120), nullable=False),
        sa.Column("current_strategy", sa.String(length=120), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("messages_count", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
    )

    op.create_table(
        "analytics_records",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("session_id", sa.String(length=120), nullable=False),
        sa.Column("scam_category", sa.String(length=100), nullable=False),
        sa.Column("successful", sa.Boolean(), nullable=False),
        sa.Column("estimated_money_saved", sa.Float(), nullable=False),
        sa.Column("time_wasted_seconds", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )

    op.create_table(
        "feedback",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("message_id", sa.String(length=120), nullable=False),
        sa.Column("label", sa.String(length=16), nullable=False),
        sa.Column("notes", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("feedback")
    op.drop_table("analytics_records")
    op.drop_table("baiting_sessions")
    op.drop_table("detection_history")
    op.drop_table("sender_profiles")
    op.drop_index("ix_users_username", table_name="users")
    op.drop_table("users")
