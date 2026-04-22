"""Normalize incidents.severity to uppercase P1-P4 and add CHECK constraint.

Revision ID: a1b2c3d4e5f7
Revises: a1b2c3d4e5f6
Create Date: 2026-04-22

See: docs/implementation/07-severity-taxonomy-migration.md
"""
from typing import Sequence, Union

from alembic import op

revision: str = "a1b2c3d4e5f7"
down_revision: Union[str, None] = "a1b2c3d4e5f6"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Normalize existing lowercase rows (e.g. "p1" -> "P1")
    op.execute(
        """
        UPDATE incidents
        SET severity = UPPER(severity)
        WHERE severity <> UPPER(severity)
        """
    )

    # 2. Add CHECK constraint to enforce canonical P1-P4 values
    op.create_check_constraint(
        "ck_incidents_severity",
        "incidents",
        "severity IN ('P1','P2','P3','P4')",
    )


def downgrade() -> None:
    op.drop_constraint("ck_incidents_severity", "incidents", type_="check")
