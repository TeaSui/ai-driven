"""Backfill agent_type='workflow' → 'coding' (legacy WorkflowAgent removal).

Revision ID: b3c4d5e6f7a8
Revises: a2b3c4d5e6f7
Create Date: 2026-05-04

The legacy WorkflowAgent path was never routed through the SQS consumer
factory (``queue/consumer.py::_create_agent`` has no ``"workflow"`` entry)
and is being removed. Historical ``tasks.agent_type='workflow'`` rows
would otherwise mis-render on the admin timeline after
``_AGENT_COMPLETED_STAGE['workflow']`` is dropped (they would fall back
to ``_STATUS_TO_STAGE['completed'] = 'deploy'`` — wrong stage).

This migration rewrites the column in-place. The operation is lossy for
analytics (you can no longer tell which completed tasks were dispatched
through the old workflow agent), which is the explicit trade the team
chose over keeping a dead code path alive for UI labeling.

Downgrade is a no-op: we cannot recover the original ``'workflow'``
values after the UPDATE commits, and re-introducing the value would
violate ``ck_tasks_agent_type`` once that check is tightened downstream.
If you need to roll back this change, restore from a backup taken
before the upgrade.
"""
from typing import Sequence, Union

from alembic import op

revision: str = "b3c4d5e6f7a8"
down_revision: Union[str, None] = "a2b3c4d5e6f7"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute(
        "UPDATE tasks SET agent_type = 'coding' WHERE agent_type = 'workflow'"
    )


def downgrade() -> None:
    # No-op: historical 'workflow' values are not recoverable after the
    # UPDATE. Restore from backup if you need them back.
    pass
