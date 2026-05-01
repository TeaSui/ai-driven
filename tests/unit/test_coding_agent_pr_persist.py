"""T19b — CodingAgent persists pr_number + pr_url with crash-safe idempotency.

Covers the shared helper ``CodingAgent._persist_and_open_pr`` used by both the
``github.create_pr`` and ``bitbucket.create_pr`` tool handlers:

  * happy path persists BOTH pr_number and pr_url as one atomic patch
  * pre-existing pr_number in ``Task.output_data`` short-circuits the SCM call
    (crash-safe SQS redelivery path)
  * 422 "pull request already exists" → recover via ``find_pr_by_branch`` and
    persist
  * 422 with unrelated detail propagates — do NOT swallow
  * non-422 errors (401, 500, etc.) propagate unchanged
  * missing task_id (debug/local path) skips persistence without crashing
  * DB error on persist is logged but does not fail the tool
  * Bitbucket path uses the same helper with the same semantics

No real Claude / SCM / DB calls — everything is mocked.
"""
from __future__ import annotations

import logging
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.coding.agent import CodingAgent
from ai_driven.scm.exceptions import SCMAuthError, SCMValidationError


# -------------------- helpers --------------------


def _fake_pr(number: int = 7, url: str = "https://github.com/o/r/pull/7") -> MagicMock:
    pr = MagicMock()
    pr.number = number
    pr.url = url
    pr.title = "T"
    return pr


def _fake_session_ctx(session: MagicMock | None = None) -> MagicMock:
    """``async with factory() as s:`` compatible context manager factory."""
    session = session or MagicMock()
    session.commit = AsyncMock()
    ctx = MagicMock()
    ctx.__aenter__ = AsyncMock(return_value=session)
    ctx.__aexit__ = AsyncMock(return_value=None)
    factory = MagicMock(return_value=ctx)
    factory._session = session
    return factory


def _make_agent(task_id: str | None = None, scm: MagicMock | None = None) -> CodingAgent:
    agent = CodingAgent(scm_client=scm or MagicMock())
    agent._task_id = task_id
    return agent


def _make_task(output_data: dict | None = None) -> MagicMock:
    t = MagicMock()
    t.output_data = output_data or {}
    return t


# -------------------- tests --------------------


@pytest.mark.asyncio
async def test_happy_path_persists_pr_number_and_url() -> None:
    """scm.create_pull_request succeeds → update_output_data called once with both keys."""
    task_id = str(uuid.uuid4())
    scm = MagicMock()
    scm.create_pull_request = AsyncMock(
        return_value=_fake_pr(42, "https://github.com/o/r/pull/42")
    )
    agent = _make_agent(task_id=task_id, scm=scm)

    task_repo = MagicMock()
    # No prior persisted PR — get_by_id returns a task with empty output_data.
    task_repo.get_by_id = AsyncMock(return_value=_make_task({}))
    task_repo.update_output_data = AsyncMock()

    with (
        patch(
            "ai_driven.agents.coding.agent.async_session_factory",
            _fake_session_ctx(),
        ),
        patch(
            "ai_driven.agents.coding.agent.TaskRepository",
            return_value=task_repo,
        ),
    ):
        result = await agent._persist_and_open_pr(
            "github",
            repo_full_name="o/r",
            title="T",
            body="B",
            source_branch="feat/x",
            target_branch="main",
        )

    assert result == {"number": 42, "url": "https://github.com/o/r/pull/42", "title": "T"}
    task_repo.update_output_data.assert_awaited_once()
    call_args = task_repo.update_output_data.await_args
    # patch is the second positional argument
    patch_arg = call_args.args[1]
    assert patch_arg == {"pr_number": 42, "pr_url": "https://github.com/o/r/pull/42"}


@pytest.mark.asyncio
async def test_idempotent_skip_when_pr_number_preexists() -> None:
    """Pre-set Task.output_data.pr_number → scm NOT called; return persisted ref."""
    task_id = str(uuid.uuid4())
    scm = MagicMock()
    scm.create_pull_request = AsyncMock()
    agent = _make_agent(task_id=task_id, scm=scm)

    task_repo = MagicMock()
    task_repo.get_by_id = AsyncMock(
        return_value=_make_task(
            {"pr_number": 99, "pr_url": "https://github.com/o/r/pull/99"}
        )
    )
    task_repo.update_output_data = AsyncMock()

    with (
        patch(
            "ai_driven.agents.coding.agent.async_session_factory",
            _fake_session_ctx(),
        ),
        patch(
            "ai_driven.agents.coding.agent.TaskRepository",
            return_value=task_repo,
        ),
    ):
        result = await agent._persist_and_open_pr(
            "github",
            repo_full_name="o/r",
            title="T",
            body="B",
            source_branch="feat/x",
            target_branch="main",
        )

    scm.create_pull_request.assert_not_awaited()
    task_repo.update_output_data.assert_not_awaited()
    assert result["number"] == 99
    assert result["url"] == "https://github.com/o/r/pull/99"
    assert result.get("idempotent") is True


@pytest.mark.asyncio
async def test_recovers_from_422_already_exists_via_find_pr_by_branch() -> None:
    """422 with 'already exists' detail → find_pr_by_branch → persist + return."""
    task_id = str(uuid.uuid4())
    already_exists_detail = {
        "message": "Validation Failed",
        "errors": [
            {
                "message": "A pull request already exists for owner:feat/x.",
                "resource": "PullRequest",
            }
        ],
    }
    scm = MagicMock()
    scm.create_pull_request = AsyncMock(
        side_effect=SCMValidationError(
            f"Validation error: {already_exists_detail}",
            detail=already_exists_detail,
        )
    )
    scm.find_pr_by_branch = AsyncMock(
        return_value=(42, "https://github.com/o/r/pull/42")
    )
    agent = _make_agent(task_id=task_id, scm=scm)

    task_repo = MagicMock()
    task_repo.get_by_id = AsyncMock(return_value=_make_task({}))
    task_repo.update_output_data = AsyncMock()

    with (
        patch(
            "ai_driven.agents.coding.agent.async_session_factory",
            _fake_session_ctx(),
        ),
        patch(
            "ai_driven.agents.coding.agent.TaskRepository",
            return_value=task_repo,
        ),
    ):
        result = await agent._persist_and_open_pr(
            "github",
            repo_full_name="o/r",
            title="T",
            body="B",
            source_branch="feat/x",
            target_branch="main",
        )

    scm.find_pr_by_branch.assert_awaited_once_with("o/r", "feat/x")
    task_repo.update_output_data.assert_awaited_once()
    patch_arg = task_repo.update_output_data.await_args.args[1]
    assert patch_arg == {"pr_number": 42, "pr_url": "https://github.com/o/r/pull/42"}
    assert result["number"] == 42
    assert result.get("recovered") is True


@pytest.mark.asyncio
async def test_422_without_already_exists_detail_propagates() -> None:
    """422 with some OTHER validation error (bad title, etc.) must NOT be swallowed."""
    task_id = str(uuid.uuid4())
    other_detail = {
        "message": "Validation Failed",
        "errors": [
            {"message": "title is too long", "resource": "PullRequest"}
        ],
    }
    scm = MagicMock()
    scm.create_pull_request = AsyncMock(
        side_effect=SCMValidationError(
            f"Validation error: {other_detail}", detail=other_detail
        )
    )
    scm.find_pr_by_branch = AsyncMock()
    agent = _make_agent(task_id=task_id, scm=scm)

    task_repo = MagicMock()
    task_repo.get_by_id = AsyncMock(return_value=_make_task({}))

    with (
        patch(
            "ai_driven.agents.coding.agent.async_session_factory",
            _fake_session_ctx(),
        ),
        patch(
            "ai_driven.agents.coding.agent.TaskRepository",
            return_value=task_repo,
        ),
    ):
        with pytest.raises(SCMValidationError):
            await agent._persist_and_open_pr(
                "github",
                repo_full_name="o/r",
                title="T" * 300,
                body="B",
                source_branch="feat/x",
                target_branch="main",
            )

    scm.find_pr_by_branch.assert_not_awaited()


@pytest.mark.asyncio
async def test_non_422_errors_propagate() -> None:
    """A 401/auth error must bubble out without entering the recovery path."""
    task_id = str(uuid.uuid4())
    scm = MagicMock()
    scm.create_pull_request = AsyncMock(
        side_effect=SCMAuthError("Authentication failed")
    )
    scm.find_pr_by_branch = AsyncMock()
    agent = _make_agent(task_id=task_id, scm=scm)

    task_repo = MagicMock()
    task_repo.get_by_id = AsyncMock(return_value=_make_task({}))

    with (
        patch(
            "ai_driven.agents.coding.agent.async_session_factory",
            _fake_session_ctx(),
        ),
        patch(
            "ai_driven.agents.coding.agent.TaskRepository",
            return_value=task_repo,
        ),
    ):
        with pytest.raises(SCMAuthError):
            await agent._persist_and_open_pr(
                "github",
                repo_full_name="o/r",
                title="T",
                body="B",
                source_branch="feat/x",
                target_branch="main",
            )

    scm.find_pr_by_branch.assert_not_awaited()


@pytest.mark.asyncio
async def test_task_id_none_skips_persist_no_crash() -> None:
    """Debug/local path with no task_id: PR still created, zero DB calls."""
    scm = MagicMock()
    scm.create_pull_request = AsyncMock(
        return_value=_fake_pr(7, "https://github.com/o/r/pull/7")
    )
    agent = _make_agent(task_id=None, scm=scm)

    factory = _fake_session_ctx()
    with patch(
        "ai_driven.agents.coding.agent.async_session_factory", factory
    ):
        result = await agent._persist_and_open_pr(
            "github",
            repo_full_name="o/r",
            title="T",
            body="B",
            source_branch="feat/x",
            target_branch="main",
        )

    assert result == {"number": 7, "url": "https://github.com/o/r/pull/7", "title": "T"}
    # No session should have been opened (both read + write skip on missing task_id).
    factory.assert_not_called()


@pytest.mark.asyncio
async def test_db_error_logs_but_returns_pr_ref(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """update_output_data raises → tool still returns PR ref; warning logged."""
    task_id = str(uuid.uuid4())
    scm = MagicMock()
    scm.create_pull_request = AsyncMock(
        return_value=_fake_pr(11, "https://github.com/o/r/pull/11")
    )
    agent = _make_agent(task_id=task_id, scm=scm)

    task_repo = MagicMock()
    task_repo.get_by_id = AsyncMock(return_value=_make_task({}))
    task_repo.update_output_data = AsyncMock(side_effect=RuntimeError("db down"))

    with (
        patch(
            "ai_driven.agents.coding.agent.async_session_factory",
            _fake_session_ctx(),
        ),
        patch(
            "ai_driven.agents.coding.agent.TaskRepository",
            return_value=task_repo,
        ),
        caplog.at_level(logging.WARNING, logger="ai_driven.agents.coding.agent"),
    ):
        result = await agent._persist_and_open_pr(
            "github",
            repo_full_name="o/r",
            title="T",
            body="B",
            source_branch="feat/x",
            target_branch="main",
        )

    assert result == {"number": 11, "url": "https://github.com/o/r/pull/11", "title": "T"}
    assert any(
        "persist_pr_ref_failed" in rec.message for rec in caplog.records
    ), "expected a warning log for the DB failure"


@pytest.mark.asyncio
async def test_bitbucket_path_parity() -> None:
    """bitbucket.create_pr goes through the same shared helper."""
    task_id = str(uuid.uuid4())
    scm = MagicMock()
    bb_pr = MagicMock()
    bb_pr.number = 3
    bb_pr.url = "https://bitbucket.org/o/r/pull-requests/3"
    bb_pr.title = "BB-T"
    scm.create_pull_request = AsyncMock(return_value=bb_pr)
    agent = _make_agent(task_id=task_id, scm=scm)

    task_repo = MagicMock()
    task_repo.get_by_id = AsyncMock(return_value=_make_task({}))
    task_repo.update_output_data = AsyncMock()

    with (
        patch(
            "ai_driven.agents.coding.agent.async_session_factory",
            _fake_session_ctx(),
        ),
        patch(
            "ai_driven.agents.coding.agent.TaskRepository",
            return_value=task_repo,
        ),
    ):
        # Call the bitbucket tool handler directly via the registry to prove
        # the registered tool dispatches through the shared helper.
        tool = agent._registry.get_tool("bitbucket.create_pr")
        assert tool is not None
        result = await tool.handler(
            repo_full_name="o/r",
            title="BB-T",
            body="B",
            source_branch="feat/x",
            target_branch="main",
        )

    assert result["number"] == 3
    assert result["url"] == "https://bitbucket.org/o/r/pull-requests/3"
    task_repo.update_output_data.assert_awaited_once()
    patch_arg = task_repo.update_output_data.await_args.args[1]
    assert patch_arg == {
        "pr_number": 3,
        "pr_url": "https://bitbucket.org/o/r/pull-requests/3",
    }
