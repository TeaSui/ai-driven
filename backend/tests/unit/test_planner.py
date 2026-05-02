"""Unit tests for the PlanningAgent — Task 3.3."""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.coding.planner import (
    ImplementationPlan,
    PlanningAgent,
)


class TestImplementationPlan:
    """Test ImplementationPlan dataclass."""

    def test_defaults(self) -> None:
        plan = ImplementationPlan()
        assert plan.summary == ""
        assert plan.files_to_create == []
        assert plan.files_to_modify == []
        assert plan.test_strategy == ""
        assert plan.risks == []
        assert plan.steps == []

    def test_from_dict_full(self) -> None:
        data = {
            "summary": "Add user auth",
            "files_to_create": [{"path": "src/auth.py", "purpose": "Auth module"}],
            "files_to_modify": [{"path": "src/app.py", "changes": "Add auth middleware"}],
            "test_strategy": "Unit tests for auth flow",
            "risks": ["Token expiry edge cases"],
            "steps": ["Step 1: Create auth module", "Step 2: Wire middleware"],
        }
        plan = ImplementationPlan.from_dict(data)
        assert plan.summary == "Add user auth"
        assert len(plan.files_to_create) == 1
        assert plan.files_to_create[0]["path"] == "src/auth.py"
        assert len(plan.files_to_modify) == 1
        assert plan.test_strategy == "Unit tests for auth flow"
        assert len(plan.risks) == 1
        assert len(plan.steps) == 2

    def test_from_dict_partial(self) -> None:
        plan = ImplementationPlan.from_dict({"summary": "Partial plan"})
        assert plan.summary == "Partial plan"
        assert plan.files_to_create == []
        assert plan.steps == []

    def test_from_dict_empty(self) -> None:
        plan = ImplementationPlan.from_dict({})
        assert plan.summary == ""


class TestParsePlan:
    """Test PlanningAgent._parse_plan static method."""

    def test_parses_raw_json(self) -> None:
        raw = json.dumps({"summary": "Test plan", "steps": ["Do things"]})
        plan = PlanningAgent._parse_plan(raw)
        assert plan.summary == "Test plan"
        assert plan.steps == ["Do things"]

    def test_parses_json_in_code_fence(self) -> None:
        text = '```json\n{"summary": "Fenced plan", "risks": ["Risk A"]}\n```'
        plan = PlanningAgent._parse_plan(text)
        assert plan.summary == "Fenced plan"
        assert plan.risks == ["Risk A"]

    def test_parses_json_in_generic_code_fence(self) -> None:
        text = '```\n{"summary": "Generic fence"}\n```'
        plan = PlanningAgent._parse_plan(text)
        assert plan.summary == "Generic fence"

    def test_invalid_json_returns_fallback(self) -> None:
        plan = PlanningAgent._parse_plan("This is not JSON at all")
        assert plan.summary == "This is not JSON at all"

    def test_invalid_json_truncates_summary_to_500(self) -> None:
        long_text = "x" * 1000
        plan = PlanningAgent._parse_plan(long_text)
        assert len(plan.summary) == 500


class TestBuildPrompt:
    """Test PlanningAgent._build_prompt."""

    def test_includes_all_sections(self) -> None:
        agent = PlanningAgent.__new__(PlanningAgent)
        prompt = agent._build_prompt(
            summary="Add feature X",
            description="Detailed description of X",
            acceptance_criteria="Must pass all tests",
            repo_structure=["src/", "src/main.py", "tests/"],
            relevant_code=[{"path": "src/main.py", "content": "def main(): pass"}],
        )
        assert "Add feature X" in prompt
        assert "Detailed description of X" in prompt
        assert "Must pass all tests" in prompt
        assert "src/main.py" in prompt
        assert "def main(): pass" in prompt

    def test_limits_repo_structure_to_200_lines(self) -> None:
        agent = PlanningAgent.__new__(PlanningAgent)
        structure = [f"file_{i}.py" for i in range(300)]
        prompt = agent._build_prompt("s", "d", "ac", structure, [])
        # file_199 should be in, file_200 should not
        assert "file_199.py" in prompt
        assert "file_200.py" not in prompt

    def test_limits_relevant_code_to_10_items(self) -> None:
        agent = PlanningAgent.__new__(PlanningAgent)
        code = [{"path": f"file_{i}.py", "content": f"content_{i}"} for i in range(15)]
        prompt = agent._build_prompt("s", "d", "ac", [], code)
        assert "file_9.py" in prompt
        assert "file_10.py" not in prompt

    def test_truncates_code_content_to_3000(self) -> None:
        agent = PlanningAgent.__new__(PlanningAgent)
        long_content = "a" * 5000
        code = [{"path": "big.py", "content": long_content}]
        prompt = agent._build_prompt("s", "d", "ac", [], code)
        # The content in prompt should be truncated to 3000 chars
        assert "a" * 3000 in prompt
        assert "a" * 3001 not in prompt


class TestCreatePlan:
    """Test PlanningAgent.create_plan with mocked Claude client."""

    @pytest.fixture
    def mock_claude(self) -> AsyncMock:
        client = AsyncMock()
        response = MagicMock()
        response.text = json.dumps({
            "summary": "Implement feature",
            "files_to_create": [{"path": "src/feature.py", "purpose": "New feature"}],
            "files_to_modify": [],
            "test_strategy": "Add unit tests",
            "risks": [],
            "steps": ["Create module", "Add tests"],
        })
        client.chat = AsyncMock(return_value=response)
        return client

    async def test_create_plan_calls_claude_with_thinking(self, mock_claude: AsyncMock) -> None:
        agent = PlanningAgent(claude_client=mock_claude)

        with patch("ai_driven.agents.coding.planner.get_settings") as mock_settings:
            settings = MagicMock()
            settings.claude_planning_model = "claude-opus-4-7"
            mock_settings.return_value = settings

            plan = await agent.create_plan(
                jira_summary="Add feature X",
                jira_description="Description",
                acceptance_criteria="Tests pass",
                repo_structure=["src/"],
                relevant_code=[],
            )

        mock_claude.chat.assert_awaited_once()
        call_kwargs = mock_claude.chat.call_args
        assert call_kwargs.kwargs["model"] == "claude-opus-4-7"
        assert call_kwargs.kwargs["thinking"]["type"] == "enabled"
        assert call_kwargs.kwargs["thinking"]["budget_tokens"] == 16000

        assert plan.summary == "Implement feature"
        assert len(plan.files_to_create) == 1
        assert len(plan.steps) == 2

    async def test_create_plan_handles_invalid_response(self, mock_claude: AsyncMock) -> None:
        mock_claude.chat.return_value.text = "Not valid JSON response"
        agent = PlanningAgent(claude_client=mock_claude)

        with patch("ai_driven.agents.coding.planner.get_settings") as mock_settings:
            mock_settings.return_value = MagicMock(claude_planning_model="test-model")

            plan = await agent.create_plan(
                jira_summary="X",
                jira_description="D",
                acceptance_criteria="AC",
                repo_structure=[],
                relevant_code=[],
            )

        assert plan.summary == "Not valid JSON response"
        assert plan.files_to_create == []
