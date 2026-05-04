# Claude stop_reason dispatch + adaptive-thinking migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Claude agent loop correctly handle every documented `stop_reason`, and migrate Opus 4.7 thinking calls from the now-rejected `{"type": "enabled", "budget_tokens": N}` form to adaptive thinking — closing two silent-failure paths in planner + every `use_thinking=True` agent call.

**Architecture:** Two coordinated changes that both live in `src/ai_driven/claude/`. (1) A new `stop_reason` dispatcher on top of `run_agent_loop` that maps every standard value to a typed outcome — including a new `ClaudeRefusalError` that callers can catch. (2) A model-keyed `resolve_thinking_config()` helper used by every site that builds a `thinking` dict, so Opus 4.7 gets `{"type": "adaptive"}` while Sonnet 4.6 / Opus 4.6 keep `{"type": "enabled", "budget_tokens": N}` until their own deprecation bites. Both providers (direct Anthropic + Bedrock) pass thinking through unchanged, so one helper fixes both paths. Tests are unit-level (mocked providers) plus one opt-in live probe we already wrote.

**Tech Stack:** Python 3.11+, `anthropic` SDK ≥ 0.34, pytest + pytest-asyncio, existing dataclass-based `MessageResponse` shape.

---

## Context — how the code is shaped today

Read this before Task 1. All line numbers relative to the feature branch baseline (`94fb31e`).

- **Agent loop:** `src/ai_driven/claude/client.py::ClaudeAgentLoop.run_agent_loop` (lines 70–210). Iterates until `not response.has_tool_use`, then returns. Only stop_reason-aware line is 209 where `"max_iterations"` is force-set when the loop times out. No branching on `pause_turn`, `refusal`, `max_tokens`, `model_context_window_exceeded`.

- **Thinking config, two hard-coded call sites today:**
  - `src/ai_driven/agents/base.py:406-408` — `if use_thinking: thinking = {"type": "enabled", "budget_tokens": 16000}`. Feeds `claude.run_agent_loop` in every `BaseAgent.run()` that opts in.
  - `src/ai_driven/agents/coding/planner.py:99-100` — explicit `{"type": "enabled", "budget_tokens": 16000}` paired with `settings.claude_planning_model` (= `claude-opus-4-7` per `config.py:54`). **This is the broken call per Anthropic's own docs** (shipped at `~/.claude/skills/building-with-the-claude-api/docs/2. Model capabilities/2. Adaptive thinking.md:24`).

- **Providers:** `src/ai_driven/claude/provider.py` — `AnthropicClaudeProvider.create_message` (line 220) and `BedrockClaudeProvider.create_message` (line 380) both take `thinking: dict[str, Any] | None` and pass it straight through (`params["thinking"] = thinking` / `body["thinking"] = thinking`). No provider-side normalization. Helper belongs in `client.py` or a new `thinking.py` — Task 2 decides.

- **Model identifiers in code:** model strings live as both app-level (`claude-opus-4-7`) and Bedrock-prefixed (`global.anthropic.claude-opus-4-7`). The thinking-config helper must strip the `global.anthropic.` prefix so one lookup works on both paths. See `provider.py:554-557` for the exact mapping.

- **Existing exception style:** no Claude-specific exception classes exist yet (`grep -rn "class.*Error\b" src/ai_driven/claude/` returns nothing). This plan introduces the first: `ClaudeRefusalError` in `src/ai_driven/claude/errors.py`.

- **Probe already committed (in-progress branch):** `tests/integration/test_opus_4_7_thinking_probe.py` proves the bad pairing exists today. Task 5 removes the static probe once the fix lands (replaced by a positive test); the live probe stays opt-in.

---

## File Structure

Files touched by this plan:

| File | Change | Purpose |
|---|---|---|
| `src/ai_driven/claude/errors.py` | **Create** | `ClaudeRefusalError` typed exception |
| `src/ai_driven/claude/thinking.py` | **Create** | `resolve_thinking_config(model: str, budget_tokens: int) -> dict \| None` |
| `src/ai_driven/claude/client.py:95-210` | Modify | Branch on `stop_reason` after non-tool-use response; raise `ClaudeRefusalError` on `"refusal"`; annotate `"max_tokens"` and `"model_context_window_exceeded"` for callers; retain `pause_turn` continuation loop |
| `src/ai_driven/claude/__init__.py` | Modify | Re-export `ClaudeRefusalError` |
| `src/ai_driven/agents/base.py:406-408` | Modify | Replace hard-coded thinking dict with `resolve_thinking_config(model, budget_tokens=16000)` |
| `src/ai_driven/agents/coding/planner.py:99-100` | Modify | Same replacement |
| `tests/unit/test_claude_errors.py` | **Create** | Unit tests for `ClaudeRefusalError` shape |
| `tests/unit/test_thinking_config.py` | **Create** | Unit tests for `resolve_thinking_config` per-model branching |
| `tests/unit/test_agent_loop_stop_reason.py` | **Create** | Unit tests for new stop_reason dispatch (mocked provider) |
| `tests/integration/test_opus_4_7_thinking_probe.py` | Modify | Flip `test_planner_ships_manual_thinking_with_opus_4_7` from "asserts bug exists" to "asserts bug is fixed" |

No existing files are restructured. Each new file has one responsibility. No test helper abstractions introduced — tests are self-contained so each can be read without jumping files.

---

## Task 1: Create `ClaudeRefusalError` exception

**Files:**
- Create: `backend/src/ai_driven/claude/errors.py`
- Test: `backend/tests/unit/test_claude_errors.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/unit/test_claude_errors.py`:

```python
"""Tests for Claude-specific exceptions."""
from __future__ import annotations

import pytest


def test_claude_refusal_error_preserves_context() -> None:
    """ClaudeRefusalError carries the refused response + message count for debugging."""
    from ai_driven.claude.errors import ClaudeRefusalError

    err = ClaudeRefusalError(
        message="Streaming safety classifier blocked output",
        model="claude-sonnet-4-6",
        refused_iteration=3,
        conversation_length=7,
    )

    assert "safety classifier" in str(err)
    assert err.model == "claude-sonnet-4-6"
    assert err.refused_iteration == 3
    assert err.conversation_length == 7


def test_claude_refusal_error_is_runtime_error_subclass() -> None:
    """ClaudeRefusalError is a RuntimeError so naive `except Exception:` catches it."""
    from ai_driven.claude.errors import ClaudeRefusalError

    assert issubclass(ClaudeRefusalError, RuntimeError)


def test_claude_refusal_error_str_is_human_readable() -> None:
    """The str() form mentions the iteration so log readers can locate the failing turn."""
    from ai_driven.claude.errors import ClaudeRefusalError

    err = ClaudeRefusalError(
        message="blocked",
        model="claude-opus-4-7",
        refused_iteration=5,
        conversation_length=11,
    )

    rendered = str(err)
    assert "iteration 5" in rendered
    assert "claude-opus-4-7" in rendered
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && pytest tests/unit/test_claude_errors.py -v`
Expected: all three tests FAIL with `ModuleNotFoundError: No module named 'ai_driven.claude.errors'`.

- [ ] **Step 3: Write minimal implementation**

Create `backend/src/ai_driven/claude/errors.py`:

```python
"""Claude-specific exceptions.

Currently exposes:
- ClaudeRefusalError: raised by the agent loop when Claude returns stop_reason="refusal".
  The skill documentation at ~/.claude/skills/building-with-the-claude-api/references/basics.md
  is explicit: on refusal, reset / remove the refused turn rather than retry — continuing
  keeps triggering refusals. We surface this as a typed exception so callers choose how to
  recover (fresh conversation, fallback model, user-visible error).
"""
from __future__ import annotations


class ClaudeRefusalError(RuntimeError):
    """Claude refused to continue generating (stop_reason="refusal").

    Not a retry-with-backoff condition — the streaming safety classifier rejected the
    output. Caller must rebuild the conversation or surface the failure to the user.
    """

    def __init__(
        self,
        message: str,
        *,
        model: str,
        refused_iteration: int,
        conversation_length: int,
    ) -> None:
        self.model = model
        self.refused_iteration = refused_iteration
        self.conversation_length = conversation_length
        super().__init__(
            f"Claude refusal at iteration {refused_iteration} "
            f"(model={model}, messages={conversation_length}): {message}"
        )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && pytest tests/unit/test_claude_errors.py -v`
Expected: `3 passed`.

- [ ] **Step 5: Re-export from package `__init__`**

Edit `backend/src/ai_driven/claude/__init__.py` — add at bottom (keep existing exports):

```python
from ai_driven.claude.errors import ClaudeRefusalError  # noqa: E402

__all__ = [*globals().get("__all__", []), "ClaudeRefusalError"]
```

(If the file has no current `__all__`, write `__all__ = ["ClaudeRefusalError"]` and add other existing public symbols in Task 4 when we start re-exporting.)

- [ ] **Step 6: Commit**

```bash
cd backend
git add src/ai_driven/claude/errors.py src/ai_driven/claude/__init__.py tests/unit/test_claude_errors.py
git commit -m "feat(claude): add ClaudeRefusalError typed exception"
```

---

## Task 2: Create `resolve_thinking_config()` helper

**Files:**
- Create: `backend/src/ai_driven/claude/thinking.py`
- Test: `backend/tests/unit/test_thinking_config.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/unit/test_thinking_config.py`:

```python
"""Tests for model-conditional thinking config resolution.

Source of truth: ~/.claude/skills/building-with-the-claude-api/docs/
  '2. Model capabilities'/'2. Adaptive thinking.md':24 —
  "On Claude Opus 4.7, adaptive thinking is the only supported thinking mode;
   manual thinking: {type: 'enabled'} is rejected with a 400 error."
"""
from __future__ import annotations

import pytest


def test_opus_4_7_returns_adaptive_config() -> None:
    """Opus 4.7 MUST use adaptive — manual `enabled` is rejected by the API."""
    from ai_driven.claude.thinking import resolve_thinking_config

    cfg = resolve_thinking_config("claude-opus-4-7", budget_tokens=16000)
    assert cfg == {"type": "adaptive"}


def test_opus_4_7_bedrock_prefix_also_returns_adaptive() -> None:
    """Bedrock model IDs (global.anthropic.claude-opus-4-7) normalize to same rule."""
    from ai_driven.claude.thinking import resolve_thinking_config

    cfg = resolve_thinking_config("global.anthropic.claude-opus-4-7", budget_tokens=16000)
    assert cfg == {"type": "adaptive"}


@pytest.mark.parametrize(
    "model",
    ["claude-sonnet-4-6", "claude-opus-4-6", "global.anthropic.claude-sonnet-4-6"],
)
def test_legacy_models_keep_enabled_budget(model: str) -> None:
    """Sonnet 4.6 / Opus 4.6 still accept (and expect) the manual budget form."""
    from ai_driven.claude.thinking import resolve_thinking_config

    cfg = resolve_thinking_config(model, budget_tokens=16000)
    assert cfg == {"type": "enabled", "budget_tokens": 16000}


def test_unknown_model_defaults_to_adaptive() -> None:
    """For any future model we haven't seen, prefer adaptive (forward-compatible)."""
    from ai_driven.claude.thinking import resolve_thinking_config

    cfg = resolve_thinking_config("claude-opus-5-0", budget_tokens=8000)
    assert cfg == {"type": "adaptive"}


def test_haiku_4_5_returns_none() -> None:
    """Haiku 4.5 does not support extended thinking — returning None signals 'omit'."""
    from ai_driven.claude.thinking import resolve_thinking_config

    cfg = resolve_thinking_config("claude-haiku-4-5", budget_tokens=16000)
    assert cfg is None


def test_budget_tokens_zero_returns_none() -> None:
    """Budget 0 means 'no thinking'; helper returns None so provider omits the param."""
    from ai_driven.claude.thinking import resolve_thinking_config

    assert resolve_thinking_config("claude-sonnet-4-6", budget_tokens=0) is None
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && pytest tests/unit/test_thinking_config.py -v`
Expected: all 7 tests FAIL with `ModuleNotFoundError: No module named 'ai_driven.claude.thinking'`.

- [ ] **Step 3: Write minimal implementation**

Create `backend/src/ai_driven/claude/thinking.py`:

```python
"""Model-conditional `thinking` parameter resolver.

Anthropic's extended-thinking API has two shapes and which one is accepted depends
on the model:

- `{"type": "adaptive"}` — Opus 4.7 REQUIRES this; manual form returns 400. Also the
  recommended form going forward on all current thinking-capable models.
- `{"type": "enabled", "budget_tokens": N}` — legacy manual form. Still works on
  Sonnet 4.6 / Opus 4.6 but deprecated.
- `None` — no thinking. Use for Haiku 4.5 (doesn't support thinking) or when the
  caller passed budget_tokens=0.

Source: Anthropic docs bundled in the skill at
~/.claude/skills/building-with-the-claude-api/docs/2. Model capabilities/2. Adaptive thinking.md
"""
from __future__ import annotations

from typing import Any

_BEDROCK_PREFIX = "global.anthropic."

# Models that REQUIRE adaptive (manual form returns 400).
_ADAPTIVE_REQUIRED = frozenset({"claude-opus-4-7"})

# Models that still support the manual `enabled`-type form.
_LEGACY_MANUAL = frozenset({"claude-sonnet-4-6", "claude-opus-4-6"})

# Models that don't support thinking at all.
_NO_THINKING = frozenset({"claude-haiku-4-5", "claude-haiku-4-5-20251001"})


def resolve_thinking_config(model: str, *, budget_tokens: int) -> dict[str, Any] | None:
    """Return the correct `thinking` parameter shape for this model, or None to omit.

    Args:
        model: Model ID. Accepts both app-level (`claude-opus-4-7`) and Bedrock-prefixed
            (`global.anthropic.claude-opus-4-7`) forms.
        budget_tokens: Desired thinking budget when manual config is accepted. Ignored
            under adaptive. A value of 0 suppresses thinking entirely (returns None).

    Returns:
        dict suitable for `messages.create(thinking=...)`, or None if thinking should
        be omitted entirely.
    """
    if budget_tokens <= 0:
        return None

    normalized = model.removeprefix(_BEDROCK_PREFIX)

    if normalized in _NO_THINKING:
        return None
    if normalized in _LEGACY_MANUAL:
        return {"type": "enabled", "budget_tokens": budget_tokens}
    # Opus 4.7 and any model we don't recognize: default to adaptive.
    # Forward-compatible — new models almost certainly use adaptive too.
    return {"type": "adaptive"}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && pytest tests/unit/test_thinking_config.py -v`
Expected: `7 passed`.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/ai_driven/claude/thinking.py tests/unit/test_thinking_config.py
git commit -m "feat(claude): add resolve_thinking_config for model-conditional thinking"
```

---

## Task 3: Wire `resolve_thinking_config` into every call site

**Files:**
- Modify: `backend/src/ai_driven/agents/base.py:405-408`
- Modify: `backend/src/ai_driven/agents/coding/planner.py:99-100`
- Modify: `backend/tests/integration/test_opus_4_7_thinking_probe.py` (flip the "asserts bug" test to "asserts fixed")

- [ ] **Step 1: Update the integration probe to assert the fix**

Open `backend/tests/integration/test_opus_4_7_thinking_probe.py`. Replace the body of `test_planner_ships_manual_thinking_with_opus_4_7` (and rename it to match the new positive assertion):

```python
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
```

Leave the `test_opus_4_7_rejects_manual_thinking_live` test untouched — it's still valuable as an opt-in sanity check.

- [ ] **Step 2: Run the renamed probe test — it should fail**

Run: `cd backend && pytest tests/integration/test_opus_4_7_thinking_probe.py::test_planner_uses_adaptive_thinking_with_opus_4_7 -v`
Expected: FAIL with `AssertionError: planner still hard-codes {type: enabled} thinking` (planner not fixed yet).

- [ ] **Step 3: Fix `agents/coding/planner.py:99-100`**

Find the block:

```python
        response = await self._claude.chat(
            messages=[{"role": "user", "content": prompt}],
            model=settings.claude_planning_model,
            thinking={"type": "enabled", "budget_tokens": 16000},
```

Replace with:

```python
        response = await self._claude.chat(
            messages=[{"role": "user", "content": prompt}],
            model=settings.claude_planning_model,
            thinking=resolve_thinking_config(settings.claude_planning_model, budget_tokens=16000),
```

Add the import near the other `ai_driven.claude.*` imports at the top of `planner.py`:

```python
from ai_driven.claude.thinking import resolve_thinking_config
```

- [ ] **Step 4: Fix `agents/base.py:405-408`**

Find the block:

```python
        thinking = None
        if use_thinking:
            thinking = {"type": "enabled", "budget_tokens": 16000}
```

Replace with:

```python
        thinking = None
        if use_thinking:
            target_model = model or settings.claude_default_model
            thinking = resolve_thinking_config(target_model, budget_tokens=16000)
```

Add the import near the other `ai_driven.claude.*` imports at the top of `base.py`:

```python
from ai_driven.claude.thinking import resolve_thinking_config
```

(`settings` is already in scope where base agent runs — confirm it's imported; if not, add `from ai_driven.config import get_settings` and the surrounding method already has a `settings = get_settings()` line near the top.)

- [ ] **Step 5: Run the probe again — now passing**

Run: `cd backend && pytest tests/integration/test_opus_4_7_thinking_probe.py::test_planner_uses_adaptive_thinking_with_opus_4_7 -v`
Expected: `1 passed`.

- [ ] **Step 6: Run the thinking-config unit tests — still passing**

Run: `cd backend && pytest tests/unit/test_thinking_config.py -v`
Expected: `7 passed`.

- [ ] **Step 7: Quick grep — no stragglers**

Run: `cd backend && grep -rn '"type": "enabled"' src/ai_driven/ tests/unit tests/integration 2>/dev/null`
Expected: zero matches in `src/ai_driven/`. Matches in `tests/` are OK only if they're *asserting* the new shape (test_thinking_config.py).

If any `src/ai_driven/` match remains — stop and fix that file the same way before proceeding.

- [ ] **Step 8: Commit**

```bash
cd backend
git add src/ai_driven/agents/base.py src/ai_driven/agents/coding/planner.py tests/integration/test_opus_4_7_thinking_probe.py
git commit -m "fix(claude): route thinking config through resolve_thinking_config

Opus 4.7 rejects manual {type: enabled, budget_tokens} with 400. Every
thinking call site now goes through resolve_thinking_config(model, ...) so
the right shape is sent per model.

Per Anthropic docs: skill/docs/2. Model capabilities/2. Adaptive thinking.md"
```

---

## Task 4: Add stop_reason dispatch to the agent loop

**Files:**
- Modify: `backend/src/ai_driven/claude/client.py::ClaudeAgentLoop.run_agent_loop` (around the non-tool-use return, ~line 146)
- Create: `backend/tests/unit/test_agent_loop_stop_reason.py`

- [ ] **Step 1: Write failing tests for each stop_reason branch**

Create `backend/tests/unit/test_agent_loop_stop_reason.py`:

```python
"""Agent-loop behavior per stop_reason.

Skill reference: ~/.claude/skills/building-with-the-claude-api/references/basics.md
— 'Handling stop_reason' table. These tests mirror that table row-by-row.
"""
from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock

import pytest

from ai_driven.claude.client import ClaudeAgentLoop
from ai_driven.claude.errors import ClaudeRefusalError
from ai_driven.claude.provider import MessageResponse, TextBlock, TokenUsage


def _response(*, stop_reason: str, text: str = "done") -> MessageResponse:
    return MessageResponse(
        content=[TextBlock(text=text)],
        stop_reason=stop_reason,
        usage=TokenUsage(input_tokens=10, output_tokens=5),
        model="claude-sonnet-4-6",
        raw_content=[],
    )


def _make_loop(provider_response: MessageResponse) -> ClaudeAgentLoop:
    """Minimal loop wired to a mock provider that returns a single fixed response."""
    provider = AsyncMock()
    provider.create_message = AsyncMock(return_value=provider_response)
    tool_registry = AsyncMock()
    tool_registry.get_schemas = AsyncMock(return_value=None)
    return ClaudeAgentLoop(provider=provider, tool_registry=tool_registry)


@pytest.mark.asyncio
async def test_end_turn_returns_response_normally() -> None:
    """end_turn is the happy path — response comes back unchanged."""
    loop = _make_loop(_response(stop_reason="end_turn", text="hello"))
    response = await loop.run_agent_loop([{"role": "user", "content": "hi"}])
    assert response.stop_reason == "end_turn"
    assert response.text == "hello"


@pytest.mark.asyncio
async def test_refusal_raises_claude_refusal_error() -> None:
    """refusal is a typed exception — callers MUST handle it, not retry blindly."""
    loop = _make_loop(_response(stop_reason="refusal"))
    with pytest.raises(ClaudeRefusalError) as exc:
        await loop.run_agent_loop([{"role": "user", "content": "banned topic"}])
    assert exc.value.refused_iteration >= 1
    assert exc.value.model == "claude-sonnet-4-6"


@pytest.mark.asyncio
async def test_max_tokens_preserves_stop_reason_on_returned_response() -> None:
    """max_tokens returns the (possibly-truncated) response; caller branches on stop_reason.

    We don't auto-retry here — that's a caller policy decision. The loop just makes the
    signal visible by leaving stop_reason='max_tokens' on the returned MessageResponse
    (and NOT overwriting it to 'max_iterations' or similar).
    """
    loop = _make_loop(_response(stop_reason="max_tokens", text="partial..."))
    response = await loop.run_agent_loop([{"role": "user", "content": "long"}])
    assert response.stop_reason == "max_tokens"
    assert response.text == "partial..."


@pytest.mark.asyncio
async def test_model_context_window_exceeded_passes_through() -> None:
    """Context-window exceeded is returned as-is so callers can chunk input / summarize."""
    loop = _make_loop(_response(stop_reason="model_context_window_exceeded"))
    response = await loop.run_agent_loop([{"role": "user", "content": "huge"}])
    assert response.stop_reason == "model_context_window_exceeded"
```

- [ ] **Step 2: Run — expect failures**

Run: `cd backend && pytest tests/unit/test_agent_loop_stop_reason.py -v`
Expected: 
- `test_end_turn_returns_response_normally` → PASS (loop already handles it)
- `test_refusal_raises_claude_refusal_error` → FAIL (no refusal branch yet)
- `test_max_tokens_preserves_stop_reason_on_returned_response` → PASS or FAIL depending on whether the current loop overwrites stop_reason; check the output.
- `test_model_context_window_exceeded_passes_through` → likely PASS (loop returns untouched when `not has_tool_use`)

Any test that already passes is fine — leave it as regression coverage.

- [ ] **Step 3: Add the `refusal` branch to `_run_agent_loop`**

Open `backend/src/ai_driven/claude/client.py`. Find the block that returns on non-tool-use responses (~line 146):

```python
            if not response.has_tool_use:
                logger.info("agent_loop_complete", extra={"iterations": iter_num, "stop_reason": response.stop_reason})
                return response
```

Replace with:

```python
            if not response.has_tool_use:
                logger.info(
                    "agent_loop_complete",
                    extra={"iterations": iter_num, "stop_reason": response.stop_reason},
                )
                if response.stop_reason == "refusal":
                    # Per Anthropic: the streaming safety classifier blocked output.
                    # Retrying with the same context only triggers another refusal —
                    # surface as typed exception so the caller resets / routes to fallback.
                    raise ClaudeRefusalError(
                        message=response.text or "(no text)",
                        model=model,
                        refused_iteration=iter_num,
                        conversation_length=len(messages),
                    )
                return response
```

Add the import near the top of `client.py` (next to other `ai_driven.claude.*` imports):

```python
from ai_driven.claude.errors import ClaudeRefusalError
```

- [ ] **Step 4: Run the stop_reason tests — expect all 4 passing**

Run: `cd backend && pytest tests/unit/test_agent_loop_stop_reason.py -v`
Expected: `4 passed`.

If `test_max_tokens_preserves_stop_reason_on_returned_response` fails because the loop overwrites stop_reason somewhere, trace the overwrite and remove it — `max_tokens` must reach the caller unchanged.

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/ai_driven/claude/client.py tests/unit/test_agent_loop_stop_reason.py
git commit -m "feat(claude): raise ClaudeRefusalError on stop_reason=refusal

Per Anthropic docs, continuing a conversation after a refusal triggers
another refusal. Surface refusals as a typed exception so callers choose
recovery (reset context, fallback model, user-facing error) rather than
loop-retry."
```

---

## Task 5: Regression sweep

**Files:** none modified — verification only.

- [ ] **Step 1: Run the full unit suite**

Run: `cd backend && /Users/tungnguyen/TYME/ai-driven/.venv/bin/pytest tests/unit -v --tb=short`

Expected: all tests pass. Specifically watch for:
- `test_provider.py` — provider contract didn't change; must stay green.
- `test_iterations_exhausted.py` — loop iteration logic untouched; must stay green.
- New tests from Tasks 1, 2, 4 — all passing.

If any pre-existing test fails, the change broke something. Do NOT commit; investigate and fix before moving on.

- [ ] **Step 2: Run the targeted integration tests**

Run: `cd backend && /Users/tungnguyen/TYME/ai-driven/.venv/bin/pytest tests/integration/test_opus_4_7_thinking_probe.py tests/integration/test_coding_flow.py tests/integration/test_review_flow.py -v --tb=short`

Expected: all pass. The probe's renamed test passes; the live probe stays skipped (no direct API key).

- [ ] **Step 3: Static check — no `"type": "enabled"` left in production code**

Run: `cd backend && grep -rn '"type": "enabled"' src/ai_driven/ 2>/dev/null`
Expected: zero matches.

Run: `cd backend && grep -rn "resolve_thinking_config" src/ai_driven/agents/ | sort`
Expected: exactly 2 import lines + 2 call sites (planner + base). Anything else suggests a stale copy or missed site.

---

## Task 6: Final commit + tidy

**Files:** none — bookkeeping only.

- [ ] **Step 1: Review commit graph**

Run: `cd backend && git log --oneline master..HEAD`
Expected: 3 commits from this plan:
1. `feat(claude): add ClaudeRefusalError typed exception`
2. `feat(claude): add resolve_thinking_config for model-conditional thinking`
3. `fix(claude): route thinking config through resolve_thinking_config`
4. `feat(claude): raise ClaudeRefusalError on stop_reason=refusal`

(Plus the pre-existing probe commit from before the plan started, if any.)

- [ ] **Step 2: Final test run from clean**

Run: `cd backend && /Users/tungnguyen/TYME/ai-driven/.venv/bin/pytest tests/unit tests/integration/test_opus_4_7_thinking_probe.py -q`

Expected: all green, no warnings about the added files.

- [ ] **Step 3: Hand back to user**

Do NOT push. Do NOT open a PR. Report status and wait for user review of the commits + any desired squashing before merge.

---

## What this plan deliberately does NOT do

- **Does not refactor `billing/tracker.py` to add the missing Haiku row.** That was a drive-by in the original survey; it's unrelated to stop_reason/thinking and belongs in its own tiny PR.
- **Does not consolidate model pricing between `metrics.py` and `tracker.py`.** Same reason.
- **Does not add structured-output (`output_config.format`) adoption.** That's a campaign, not a fix — called out in the original Tier 1 survey as "refactor opportunistically." A separate plan will handle it when the first parser site is touched.
- **Does not change the Bedrock provider's behavior.** It currently passes `thinking` through unchanged; our helper produces the right shape upstream so no provider-side work is needed. If the Bedrock runtime later rejects adaptive thinking, we handle it then — we're not guessing platform behavior now.
- **Does not change `output_config` / `effort` level.** The skill mentions `effort="xhigh"` as a companion to adaptive on Opus 4.7. We're leaving effort at the SDK default (`high`) until we have an eval showing xhigh is worth the latency. Documented here so the decision is recorded.
