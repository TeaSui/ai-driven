"""Tests for WorkflowTriggers."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from ai_driven.agents.workflow.triggers import WorkflowTriggers


class FakeProducer:
    def __init__(self) -> None:
        self.enqueued: list[dict] = []

    async def enqueue_task(
        self, task_id: str, agent_type: str, payload: dict
    ) -> str:
        self.enqueued.append(
            {"task_id": task_id, "agent_type": agent_type, "payload": payload}
        )
        return "msg-fake"


class FakeNotifier:
    def __init__(self) -> None:
        self.phase_changes: list[dict] = []
        self.completed: list[dict] = []
        self.failed: list[dict] = []

    async def notify_phase_change(
        self, task_id: str, phase: str, detail: str = ""
    ) -> None:
        self.phase_changes.append({"task_id": task_id, "phase": phase, "detail": detail})

    async def notify_task_completed(
        self, task_id: str, pr_url: str | None = None
    ) -> None:
        self.completed.append({"task_id": task_id, "pr_url": pr_url})

    async def notify_task_failed(self, task_id: str, error: str) -> None:
        self.failed.append({"task_id": task_id, "error": error})


@pytest.fixture()
def producer() -> FakeProducer:
    return FakeProducer()


@pytest.fixture()
def notifier() -> FakeNotifier:
    return FakeNotifier()


@pytest.fixture()
def scm() -> MagicMock:
    client = MagicMock()
    client.merge_pull_request = AsyncMock()
    return client


# ---------------------------------------------------------------------------
# on_coding_task_completed
# ---------------------------------------------------------------------------


class TestOnCodingTaskCompleted:
    @pytest.mark.asyncio
    async def test_dispatches_review_when_pr_created(
        self, producer: FakeProducer, notifier: FakeNotifier
    ) -> None:
        triggers = WorkflowTriggers(
            task_producer=producer, slack_notifier=notifier
        )
        await triggers.on_coding_task_completed(
            task_id="task-1",
            result={
                "pr_number": 42,
                "repo_full_name": "org/repo",
                "pr_title": "feat: login",
            },
        )
        assert len(producer.enqueued) == 1
        assert producer.enqueued[0]["agent_type"] == "review"
        assert producer.enqueued[0]["payload"]["pr_number"] == 42

        assert len(notifier.phase_changes) == 1
        assert "PR Created" in notifier.phase_changes[0]["phase"]

    @pytest.mark.asyncio
    async def test_skips_review_when_no_pr(self, producer: FakeProducer) -> None:
        triggers = WorkflowTriggers(task_producer=producer)
        await triggers.on_coding_task_completed(
            task_id="task-2",
            result={"output": "no PR created"},
        )
        assert len(producer.enqueued) == 0

    @pytest.mark.asyncio
    async def test_skips_review_when_no_repo(self, producer: FakeProducer) -> None:
        triggers = WorkflowTriggers(task_producer=producer)
        await triggers.on_coding_task_completed(
            task_id="task-3",
            result={"pr_number": 1},
        )
        assert len(producer.enqueued) == 0


# ---------------------------------------------------------------------------
# on_review_completed
# ---------------------------------------------------------------------------


class TestOnReviewCompleted:
    @pytest.mark.asyncio
    async def test_merges_when_approved(
        self, scm: MagicMock, notifier: FakeNotifier
    ) -> None:
        triggers = WorkflowTriggers(scm_client=scm, slack_notifier=notifier)
        await triggers.on_review_completed(
            task_id="task-10",
            result={
                "verdict": "approved",
                "repo_full_name": "org/repo",
                "pr_number": 55,
            },
        )
        scm.merge_pull_request.assert_awaited_once_with("org/repo", 55, "squash")
        assert len(notifier.phase_changes) == 1
        assert "Merged" in notifier.phase_changes[0]["phase"]

    @pytest.mark.asyncio
    async def test_notifies_when_changes_requested(
        self, notifier: FakeNotifier
    ) -> None:
        triggers = WorkflowTriggers(slack_notifier=notifier)
        await triggers.on_review_completed(
            task_id="task-11",
            result={
                "verdict": "changes_requested",
                "repo_full_name": "org/repo",
                "pr_number": 56,
                "summary": "Fix the tests",
            },
        )
        assert len(notifier.phase_changes) == 1
        assert "Changes Requested" in notifier.phase_changes[0]["phase"]
        assert "Fix the tests" in notifier.phase_changes[0]["detail"]

    @pytest.mark.asyncio
    async def test_merge_failure_notifies_error(
        self, scm: MagicMock, notifier: FakeNotifier
    ) -> None:
        scm.merge_pull_request.side_effect = RuntimeError("conflict")
        triggers = WorkflowTriggers(scm_client=scm, slack_notifier=notifier)
        await triggers.on_review_completed(
            task_id="task-12",
            result={
                "verdict": "approved",
                "repo_full_name": "org/repo",
                "pr_number": 57,
            },
        )
        assert len(notifier.failed) == 1
        assert "Auto-merge failed" in notifier.failed[0]["error"]

    @pytest.mark.asyncio
    async def test_unknown_verdict_no_action(self) -> None:
        triggers = WorkflowTriggers()
        # Should not raise
        await triggers.on_review_completed(
            task_id="task-13",
            result={"verdict": "pending", "repo_full_name": "org/repo", "pr_number": 58},
        )


# ---------------------------------------------------------------------------
# on_merge_completed
# ---------------------------------------------------------------------------


class TestOnMergeCompleted:
    @pytest.mark.asyncio
    async def test_notifies_team(self, notifier: FakeNotifier) -> None:
        triggers = WorkflowTriggers(slack_notifier=notifier)
        await triggers.on_merge_completed(
            task_id="task-20",
            result={
                "repo_full_name": "org/repo",
                "pr_number": 60,
                "pr_url": "https://github.com/org/repo/pull/60",
            },
        )
        assert len(notifier.completed) == 1
        assert notifier.completed[0]["pr_url"] == "https://github.com/org/repo/pull/60"

    @pytest.mark.asyncio
    async def test_works_without_notifier(self) -> None:
        triggers = WorkflowTriggers()
        # Should not raise
        await triggers.on_merge_completed(
            task_id="task-21", result={"pr_number": 61}
        )
