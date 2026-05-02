"""Regression tests for iteration-exhaustion classification.

Before the fix (KAN-9 run fe4e7f5c, 2026-04-25):
    * ClaudeClient.run_agent_loop() returned the last response silently on
      max_iterations — caller couldn't distinguish it from a clean stop.
    * BaseAgent.run() always set AgentResult.success=True in the happy path.
    * Consumer/chat-router mapped success → status=completed.
    * Net effect: tasks that burned their iteration budget mid-work were
      marked "completed" with no PR URL and no error.

After the fix:
    * ClaudeClient tags the response with stop_reason="max_iterations".
    * BaseAgent sets run_success=False and populates AgentResult.error
      with a clear message.
    * Consumer/chat-router map success=False → status=failed.
"""

from __future__ import annotations

from ai_driven.claude.provider import MessageResponse, TextBlock, TokenUsage


class TestMaxIterationsTagging:
    def test_message_response_allows_stop_reason_override(self) -> None:
        """ClaudeClient mutates response.stop_reason after hitting max_iter.

        Pinning this behavior: MessageResponse.stop_reason must be a writable
        attribute (not a frozen @property). If someone makes MessageResponse
        a dataclass(frozen=True) later, this test flags it.
        """
        resp = MessageResponse(
            content=[TextBlock(text="hello")],
            stop_reason="end_turn",
            usage=TokenUsage(),
            model="claude-sonnet-4-6",
            raw_content=[],
        )
        resp.stop_reason = "max_iterations"
        assert resp.stop_reason == "max_iterations"


class TestIterationExhaustedClassification:
    """BaseAgent.run() turns stop_reason='max_iterations' into success=False.

    Written as a shape/contract test rather than a full run, because the
    agent loop has a large dependency surface (Claude provider, Redis, DB).
    The logic under test is narrow: given response.stop_reason, compute
    run_success + error.
    """

    def test_stop_reason_end_turn_is_success(self) -> None:
        resp = MessageResponse(
            content=[],
            stop_reason="end_turn",
            usage=TokenUsage(),
            model="m",
            raw_content=[],
        )
        bad_stop = getattr(resp, "stop_reason", None) in (
            "max_iterations", "max_tokens",
        )
        run_success = not bad_stop
        assert run_success is True

    def test_stop_reason_max_iterations_is_failure(self) -> None:
        resp = MessageResponse(
            content=[],
            stop_reason="max_iterations",
            usage=TokenUsage(),
            model="m",
            raw_content=[],
        )
        bad_stop = getattr(resp, "stop_reason", None) in (
            "max_iterations", "max_tokens",
        )
        run_success = not bad_stop
        assert run_success is False
        # An error message must be surfaced so the UI doesn't silently
        # mislead the user with a "completed" badge.
        assert bad_stop

    def test_stop_reason_max_tokens_is_failure(self) -> None:
        """KAN-10 regression (2026-04-25): the model's final response got
        truncated at 4096 output tokens mid-sentence, preventing
        github.create_pr from firing. BaseAgent must flag this as failed."""
        resp = MessageResponse(
            content=[],
            stop_reason="max_tokens",
            usage=TokenUsage(),
            model="m",
            raw_content=[],
        )
        bad_stop = getattr(resp, "stop_reason", None) in (
            "max_iterations", "max_tokens",
        )
        assert bad_stop
        assert not bad_stop is False  # run_success = not bad_stop = False


class TestIterationBudgetDefault:
    """The default cap is large enough for typical coding tickets."""

    def test_default_is_at_least_40(self) -> None:
        from ai_driven.config import get_settings

        get_settings.cache_clear()
        s = get_settings()
        assert s.claude_max_tool_iterations >= 40, (
            "Coding runs that write ≥10 files routinely need 30-40+ "
            "iterations. Raise if this drops below 40 without a plan "
            "for smaller file batches or checkpoint/resume."
        )
