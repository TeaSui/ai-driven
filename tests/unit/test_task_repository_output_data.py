"""T19b — TaskRepository.update_output_data merge semantics.

``update_status(..., output_data=...)`` OVERWRITES the whole JSON column,
silently dropping any sibling keys written by a prior caller (e.g. one path
writes ``branch_name``, another writes ``pr_number`` — second write wins,
first write is lost). ``update_output_data`` does a read-modify-write so
both keys survive.

These unit tests drive the merge with a mocked session: one ``session.get``
call to fetch the current Task, one ``session.execute`` call carrying an
UPDATE with the merged dict. We assert on the ``values()`` bound to the
UPDATE rather than hitting a real DB — the merge logic is pure Python and
fully covered this way.
"""
from __future__ import annotations

import uuid
from unittest.mock import AsyncMock, MagicMock

import pytest

from ai_driven.db.postgres.repository import TaskRepository


def _make_task(output_data: dict) -> MagicMock:
    t = MagicMock()
    t.output_data = output_data
    return t


def _extract_update_values(session_mock: MagicMock) -> dict:
    """Pull the ``values`` dict out of the last session.execute call.

    Our UPDATE statement is built as ``update(Task).where(...).values(**kw)``
    so the bound values live on the compiled statement. We call
    ``.compile().params`` to read them back.
    """
    assert session_mock.execute.await_count >= 1
    stmt = session_mock.execute.await_args.args[0]
    compiled = stmt.compile()
    return dict(compiled.params)


@pytest.mark.asyncio
async def test_update_output_data_preserves_existing_keys() -> None:
    """Pre-write output_data={'other': 1} → update with {'pr_number': 42} → both present."""
    task_id = uuid.uuid4()
    session = MagicMock()
    session.get = AsyncMock(return_value=_make_task({"other": 1, "branch_name": "feat/x"}))
    session.execute = AsyncMock()

    repo = TaskRepository(session)
    await repo.update_output_data(task_id, {"pr_number": 42})

    params = _extract_update_values(session)
    # SQLAlchemy binds parameters under the column name (``output_data``) by
    # default, but the compiled.params dict key can vary — find the JSON arg
    # by looking for a dict value.
    output_val = next(v for v in params.values() if isinstance(v, dict))
    assert output_val == {"other": 1, "branch_name": "feat/x", "pr_number": 42}


@pytest.mark.asyncio
async def test_update_output_data_overrides_existing_pr_number() -> None:
    """Pre-write {'pr_number': 1} → update with {'pr_number': 2} → new value wins."""
    task_id = uuid.uuid4()
    session = MagicMock()
    session.get = AsyncMock(
        return_value=_make_task({"pr_number": 1, "other": "keep"})
    )
    session.execute = AsyncMock()

    repo = TaskRepository(session)
    await repo.update_output_data(task_id, {"pr_number": 2})

    params = _extract_update_values(session)
    output_val = next(v for v in params.values() if isinstance(v, dict))
    assert output_val == {"pr_number": 2, "other": "keep"}


@pytest.mark.asyncio
async def test_update_output_data_empty_patch_is_noop() -> None:
    """Empty patch → no UPDATE issued (avoid needless round trip)."""
    task_id = uuid.uuid4()
    session = MagicMock()
    session.get = AsyncMock()
    session.execute = AsyncMock()

    repo = TaskRepository(session)
    await repo.update_output_data(task_id, {})

    session.execute.assert_not_awaited()
    session.get.assert_not_awaited()


@pytest.mark.asyncio
async def test_update_output_data_none_output_data_starts_empty() -> None:
    """When output_data is None (legacy rows), merge treats it as {}."""
    task_id = uuid.uuid4()
    session = MagicMock()
    session.get = AsyncMock(return_value=_make_task(None))  # type: ignore[arg-type]
    session.execute = AsyncMock()

    repo = TaskRepository(session)
    await repo.update_output_data(
        task_id, {"pr_number": 5, "pr_url": "https://x/5"}
    )

    params = _extract_update_values(session)
    output_val = next(v for v in params.values() if isinstance(v, dict))
    assert output_val == {"pr_number": 5, "pr_url": "https://x/5"}


@pytest.mark.asyncio
async def test_update_output_data_missing_task_is_noop() -> None:
    """If the task row has been deleted, skip the UPDATE rather than crashing."""
    task_id = uuid.uuid4()
    session = MagicMock()
    session.get = AsyncMock(return_value=None)
    session.execute = AsyncMock()

    repo = TaskRepository(session)
    await repo.update_output_data(task_id, {"pr_number": 9})

    session.execute.assert_not_awaited()
