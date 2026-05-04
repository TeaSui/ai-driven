"""Probe: does Opus 4.7 reject manual extended-thinking config?

This test exists to verify a claim in the `building-with-the-claude-api` skill
before we ship a model-conditional fix. The skill says:

> Opus 4.7 rejects manual thinking. Use `thinking={"type": "adaptive"}` +
> `output_config={"effort": "xhigh"}`.

If that claim is true, our current code path
(`agents/coding/planner.py:99-100` pairing `claude-opus-4-7` with
`{"type": "enabled", "budget_tokens": 16000}`) is broken on every planning
call.

Two probes:

1. `test_planner_ships_manual_thinking_with_opus_4_7` — static unit probe
   proving the problematic pairing exists in source. No API cost, runs in CI.

2. `test_opus_4_7_rejects_manual_thinking_live` — opt-in integration probe
   that makes one live API call and asserts the 400. Gated by `eval` marker
   (see `tests/evals/README.md`). Costs a few tokens. Run before shipping
   the fix to confirm the claim against the real API.
"""
from __future__ import annotations

import os

import pytest


def test_planner_uses_adaptive_thinking_with_opus_4_7() -> None:
    """Coding planner must use adaptive thinking when paired with Opus 4.7.

    The pairing of `claude-opus-4-7` + `{"type": "enabled", ...}` returns 400
    from the real API — so the planner must go through resolve_thinking_config()
    rather than hard-coding the manual form.
    """
    from pathlib import Path

    planner_src = Path(__file__).resolve().parents[2] / "src" / "ai_driven" / "agents" / "coding" / "planner.py"
    text = planner_src.read_text()

    # Planner still references the planning model (sanity check).
    assert 'claude_planning_model' in text, "planner no longer uses claude_planning_model"

    # The manual `enabled`-type form must NOT appear inline anywhere in the planner —
    # it goes through resolve_thinking_config() now.
    assert '"type": "enabled"' not in text, (
        "planner still hard-codes {type: enabled} thinking — should use "
        "resolve_thinking_config(model, budget_tokens=...) so Opus 4.7 gets adaptive"
    )
    assert "resolve_thinking_config" in text, (
        "planner must import and call resolve_thinking_config"
    )


@pytest.mark.eval
@pytest.mark.asyncio
async def test_opus_4_7_rejects_manual_thinking_live() -> None:
    """Live probe: Opus 4.7 returns 400 when given manual `enabled`-type thinking.

    Gated by `eval` marker. Run with:
        pytest -m eval tests/integration/test_opus_4_7_thinking_probe.py

    Requires ANTHROPIC_API_KEY. Costs a handful of tokens (request is rejected
    before generation, so we only pay for a failed request's input tokens).
    """
    if not os.environ.get("ANTHROPIC_API_KEY"):
        pytest.skip("ANTHROPIC_API_KEY not set")

    import anthropic

    client = anthropic.AsyncAnthropic()

    with pytest.raises(anthropic.BadRequestError) as exc_info:
        await client.messages.create(
            model="claude-opus-4-7",
            max_tokens=1024,
            thinking={"type": "enabled", "budget_tokens": 1024},
            messages=[{"role": "user", "content": "ping"}],
        )

    # Expect a 400 mentioning thinking / adaptive so the error is actionable.
    err_msg = str(exc_info.value).lower()
    assert "thinking" in err_msg or "adaptive" in err_msg or "effort" in err_msg, (
        f"Opus 4.7 returned a 400 but the error didn't reference thinking/adaptive/effort. "
        f"The skill's claim may be outdated or the error message may have changed. "
        f"Actual error: {exc_info.value!r}"
    )
