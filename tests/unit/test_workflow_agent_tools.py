"""Tests for WorkflowAgent tool registration and execution."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.workflow.agent import WorkflowAgent


class FakeProducer:
    """In-memory task producer for testing."""

    def __init__(self) -> None:
        self.enqueued: list[dict] = []

    async def enqueue_task(
        self, task_id: str, agent_type: str, payload: dict
    ) -> str:
        self.enqueued.append(
            {"task_id": task_id, "agent_type": agent_type, "payload": payload}
        )
        return "msg-fake-123"


class FakeNotifier:
    """In-memory Slack notifier for testing."""

    def __init__(self) -> None:
        self.sent: list[str] = []

    async def _send(self, text: str, channel: str | None = None) -> None:
        self.sent.append(text)


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


@pytest.fixture()
def agent(producer: FakeProducer, notifier: FakeNotifier, scm: MagicMock) -> WorkflowAgent:
    return WorkflowAgent(
        scm_client=scm,
        task_producer=producer,
        slack_notifier=notifier,
    )


# ---------------------------------------------------------------------------
# Tool registration
# ---------------------------------------------------------------------------


class TestToolRegistration:
    def test_all_tools_registered(self, agent: WorkflowAgent) -> None:
        names = agent._registry.tool_names
        assert "workflow.dispatch_coding" in names
        assert "workflow.dispatch_review" in names
        assert "workflow.check_task_status" in names
        assert "github.merge_pr" in names
        assert "slack.notify_team" in names

    def test_tool_count(self, agent: WorkflowAgent) -> None:
        assert len(agent._registry) == 5


# ---------------------------------------------------------------------------
# dispatch_coding_agent
# ---------------------------------------------------------------------------


class TestDispatchCodingAgent:
    @pytest.mark.asyncio
    async def test_enqueues_coding_task(self, agent: WorkflowAgent, producer: FakeProducer) -> None:
        result = await agent._registry.execute(
            "workflow.dispatch_coding",
            {
                "jira_key": "PROJ-42",
                "repo_full_name": "org/repo",
                "title": "Implement login",
            },
        )
        assert result["agent_type"] == "coding"
        assert result["status"] == "queued"
        assert result["jira_key"] == "PROJ-42"
        assert result["message_id"] == "msg-fake-123"

        assert len(producer.enqueued) == 1
        msg = producer.enqueued[0]
        assert msg["agent_type"] == "coding"
        assert msg["payload"]["jira_key"] == "PROJ-42"

    @pytest.mark.asyncio
    async def test_returns_error_without_producer(self) -> None:
        agent = WorkflowAgent(task_producer=None)
        result = await agent._registry.execute(
            "workflow.dispatch_coding",
            {"jira_key": "X-1", "repo_full_name": "o/r", "title": "t"},
        )
        assert "error" in result


# ---------------------------------------------------------------------------
# dispatch_review_agent
# ---------------------------------------------------------------------------


class TestDispatchReviewAgent:
    @pytest.mark.asyncio
    async def test_enqueues_review_task(self, agent: WorkflowAgent, producer: FakeProducer) -> None:
        result = await agent._registry.execute(
            "workflow.dispatch_review",
            {
                "repo_full_name": "org/repo",
                "pr_number": 99,
                "pr_title": "feat: add login",
            },
        )
        assert result["agent_type"] == "review"
        assert result["pr_number"] == 99
        assert len(producer.enqueued) == 1


# ---------------------------------------------------------------------------
# check_task_status
# ---------------------------------------------------------------------------


class TestCheckTaskStatus:
    @pytest.mark.asyncio
    async def test_returns_unknown_when_no_transitions(self, agent: WorkflowAgent) -> None:
        with patch(
            "ai_driven.db.dynamo.models.TaskStateMachineStore"
        ) as MockStore:
            MockStore.return_value.get_transitions.return_value = []
            result = await agent._registry.execute(
                "workflow.check_task_status", {"task_id": "abc-123"}
            )
        assert result["status"] == "unknown"

    @pytest.mark.asyncio
    async def test_returns_latest_state(self, agent: WorkflowAgent) -> None:
        with patch(
            "ai_driven.db.dynamo.models.TaskStateMachineStore"
        ) as MockStore:
            MockStore.return_value.get_transitions.return_value = [
                {"from_state": "TICKET_CREATED", "to_state": "READY_FOR_DEV", "state_timestamp": "t1"},
                {"from_state": "READY_FOR_DEV", "to_state": "CODING", "state_timestamp": "t2"},
            ]
            result = await agent._registry.execute(
                "workflow.check_task_status", {"task_id": "abc-123"}
            )
        assert result["status"] == "CODING"
        assert result["transition_count"] == 2


# ---------------------------------------------------------------------------
# merge_pull_request
# ---------------------------------------------------------------------------


class TestMergePullRequest:
    @pytest.mark.asyncio
    async def test_merge_success(self, agent: WorkflowAgent, scm: MagicMock) -> None:
        result = await agent._registry.execute(
            "github.merge_pr",
            {"repo_full_name": "org/repo", "pr_number": 42},
        )
        assert result["status"] == "merged"
        scm.merge_pull_request.assert_awaited_once_with("org/repo", 42, "squash")

    @pytest.mark.asyncio
    async def test_merge_failure(self, agent: WorkflowAgent, scm: MagicMock) -> None:
        scm.merge_pull_request.side_effect = RuntimeError("conflict")
        result = await agent._registry.execute(
            "github.merge_pr",
            {"repo_full_name": "org/repo", "pr_number": 42},
        )
        assert "error" in result
        assert "conflict" in result["error"]

    @pytest.mark.asyncio
    async def test_merge_without_scm(self) -> None:
        agent = WorkflowAgent(scm_client=None)
        result = await agent._registry.execute(
            "github.merge_pr",
            {"repo_full_name": "org/repo", "pr_number": 42},
        )
        assert "error" in result


# ---------------------------------------------------------------------------
# notify_team
# ---------------------------------------------------------------------------


class TestNotifyTeam:
    @pytest.mark.asyncio
    async def test_sends_notification(
        self, agent: WorkflowAgent, notifier: FakeNotifier
    ) -> None:
        result = await agent._registry.execute(
            "slack.notify_team", {"message": "Deploy done"}
        )
        assert result["status"] == "sent"
        assert "Deploy done" in notifier.sent

    @pytest.mark.asyncio
    async def test_notify_without_notifier(self) -> None:
        agent = WorkflowAgent(slack_notifier=None)
        result = await agent._registry.execute(
            "slack.notify_team", {"message": "hello"}
        )
        assert "error" in result
