# LangGraph Migration — Phase 0 (LangSmith) + Phase 1 (LangChain model layer) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add LangSmith tracing to the existing custom stack (zero behavior change), then implement a LangChain-backed model provider behind the existing `ClaudeProvider` interface, config-flagged off by default.

**Architecture:** Per `docs/design/langgraph-migration.md` (FINAL v3). Phase 0 wraps the two concrete providers' `create_message` with an inert-by-default `@traceable` decorator carrying PII masking. Phase 1 adds `LangChainProvider(ClaudeProvider)` using `ChatAnthropic` / `ChatBedrockConverse`, selected by a new `claude_provider_impl` setting (`legacy` default). Nothing upstream of `create_provider()` changes.

**Tech Stack:** Python 3.12, pytest + pytest-asyncio, `langsmith`, `langchain-core`, `langchain-anthropic>=1.1`, `langchain-aws`.

**Security rules:** This plan touches a trust boundary (external vendor egress of LLM traces). Implementers MUST read `docs/security/langsmith-tracing.md` (produced by Security before implementation; if that file does not exist, STOP and return NEEDS_CONTEXT). Hard rules regardless: LangSmith disabled unless `AI_DRIVEN_LANGSMITH_ENABLED=true`; API key only via `LANGSMITH_API_KEY` env (never a settings default, never committed); masking ON whenever tracing is on.

**Verification context:** All library API usage in Phase 1 (langchain message formats, `usage_metadata` fields, `astream` chunk shapes) MUST be verified against the installed package via context7 or the package source before implementation — the code below is the design intent; the test assertions are the contract. If the installed API differs, adjust implementation, keep the test contract.

---

## Phase 0 — LangSmith tracing (zero behavior change)

### Task 1: Dependencies + settings

**Files:**
- Modify: `backend/pyproject.toml` (dependencies list)
- Modify: `backend/src/ai_driven/config.py` (Settings class, after the Claude block ~line 95)
- Test: `backend/tests/unit/test_config_langsmith.py`

- [ ] **Step 1: Write the failing test**

```python
"""backend/tests/unit/test_config_langsmith.py"""
from ai_driven.config import Settings


def test_langsmith_disabled_by_default():
    s = Settings(_env_file=None)
    assert s.langsmith_enabled is False
    assert s.langsmith_project == "ai-driven-dev"
    assert s.langsmith_mask_payloads is True


def test_langsmith_has_no_api_key_setting():
    # The API key must come ONLY from the LANGSMITH_API_KEY env var read by
    # the langsmith SDK itself — never a pydantic setting that could end up
    # in dumps/logs.
    assert not hasattr(Settings(_env_file=None), "langsmith_api_key")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/unit/test_config_langsmith.py -v`
Expected: FAIL — `AttributeError: 'Settings' object has no attribute 'langsmith_enabled'`

- [ ] **Step 3: Add settings + dependency**

In `backend/src/ai_driven/config.py`, after the Claude settings block:

```python
    # LangSmith tracing (Phase 0 of docs/design/langgraph-migration.md).
    # Disabled by default in every environment. Enabling requires BOTH
    # this flag AND the LANGSMITH_API_KEY env var (read by the langsmith
    # SDK directly — deliberately not a Settings field). Masking is ON
    # by default and MUST stay on in prod until the PII residency
    # decision in design §4 is signed off.
    langsmith_enabled: bool = False
    langsmith_project: str = "ai-driven-dev"
    langsmith_mask_payloads: bool = True
```

In `backend/pyproject.toml` dependencies, after `"anthropic>=0.34",`:

```toml
  "langsmith>=0.4",
```

- [ ] **Step 4: Install and run test to verify it passes**

Run: `cd backend && pip install -e . -q && python -m pytest tests/unit/test_config_langsmith.py -v`
Expected: 2 PASS

- [ ] **Step 5: Commit**

```bash
git add backend/pyproject.toml backend/src/ai_driven/config.py backend/tests/unit/test_config_langsmith.py
git commit -m "feat(observability): add langsmith dep and disabled-by-default settings"
```

### Task 2: Tracing module with masking

**Files:**
- Create: `backend/src/ai_driven/observability/langsmith_tracing.py`
- Test: `backend/tests/unit/test_langsmith_tracing.py`

- [ ] **Step 1: Write the failing tests**

```python
"""backend/tests/unit/test_langsmith_tracing.py"""
import pytest

from ai_driven.observability.langsmith_tracing import (
    mask_inputs,
    mask_outputs,
    traced_llm,
)


def test_mask_inputs_redacts_message_content_and_system():
    masked = mask_inputs({
        "model": "claude-sonnet-4-6",
        "system": "You are a fintech agent. Customer: John, +63-917-555-0100",
        "messages": [{"role": "user", "content": "ticket body with PII"}],
        "max_tokens": 8192,
    })
    assert masked["model"] == "claude-sonnet-4-6"          # metadata survives
    assert masked["max_tokens"] == 8192
    assert masked["system"] == "<redacted:60chars>"
    assert masked["messages"] == [{"role": "user", "content": "<redacted>"}]


def test_mask_outputs_redacts_content_keeps_usage():
    class FakeUsage:
        input_tokens = 10
        output_tokens = 5
    class FakeResponse:
        content = ["whatever"]
        stop_reason = "end_turn"
        model = "claude-sonnet-4-6"
        usage = FakeUsage()
    masked = mask_outputs(FakeResponse())
    assert masked["stop_reason"] == "end_turn"
    assert masked["model"] == "claude-sonnet-4-6"
    assert masked["usage"] == {"input_tokens": 10, "output_tokens": 5}
    assert "content" not in masked


@pytest.mark.asyncio
async def test_traced_llm_is_passthrough_when_disabled(monkeypatch):
    # langsmith_enabled defaults False — the decorator must not alter
    # behavior, signature, or return value, and must not require an API key.
    calls = []

    @traced_llm(name="test_call")
    async def fake_create(**kwargs):
        calls.append(kwargs)
        return {"ok": True}

    result = await fake_create(model="m", messages=[])
    assert result == {"ok": True}
    assert calls == [{"model": "m", "messages": []}]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && python -m pytest tests/unit/test_langsmith_tracing.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'ai_driven.observability.langsmith_tracing'`

- [ ] **Step 3: Implement the module**

```python
"""backend/src/ai_driven/observability/langsmith_tracing.py

LangSmith tracing — Phase 0 of docs/design/langgraph-migration.md.

Inert unless settings.langsmith_enabled is True AND LANGSMITH_API_KEY is
set in the environment. When enabled, payload masking is applied per
settings.langsmith_mask_payloads (default True) — message/system content
is redacted; model names, token counts, stop reasons, and timing survive,
which is what cost/latency analysis needs. See docs/security/langsmith-tracing.md.
"""

from __future__ import annotations

import functools
import logging
import os
from typing import Any, Callable

from ai_driven.config import get_settings

logger = logging.getLogger(__name__)


def mask_inputs(inputs: dict[str, Any]) -> dict[str, Any]:
    """Redact prompt/message content; keep call metadata."""
    masked = dict(inputs)
    if isinstance(masked.get("system"), str):
        masked["system"] = f"<redacted:{len(masked['system'])}chars>"
    elif masked.get("system") is not None:
        masked["system"] = "<redacted>"
    if isinstance(masked.get("messages"), list):
        masked["messages"] = [
            {"role": m.get("role", "?"), "content": "<redacted>"}
            for m in masked["messages"]
            if isinstance(m, dict)
        ]
    if "tools" in masked and masked["tools"]:
        masked["tools"] = f"<redacted:{len(masked['tools'])} tools>"
    return masked


def mask_outputs(response: Any) -> dict[str, Any]:
    """Keep stop_reason / model / usage; drop content blocks."""
    usage = getattr(response, "usage", None)
    return {
        "stop_reason": getattr(response, "stop_reason", None),
        "model": getattr(response, "model", None),
        "usage": {
            "input_tokens": getattr(usage, "input_tokens", 0),
            "output_tokens": getattr(usage, "output_tokens", 0),
        },
    }


def _tracing_active() -> bool:
    return get_settings().langsmith_enabled and bool(os.environ.get("LANGSMITH_API_KEY"))


def traced_llm(name: str) -> Callable:
    """Decorator: LangSmith @traceable(run_type="llm") when active, no-op otherwise.

    The active/inactive decision is made PER CALL (not at import) so tests
    and runtime flag flips behave predictably.
    """
    def decorator(func: Callable) -> Callable:
        @functools.wraps(func)
        async def wrapper(*args: Any, **kwargs: Any) -> Any:
            if not _tracing_active():
                return await func(*args, **kwargs)
            settings = get_settings()
            from langsmith import traceable  # imported lazily; dep always present

            traced = traceable(
                run_type="llm",
                name=name,
                project_name=settings.langsmith_project,
                process_inputs=mask_inputs if settings.langsmith_mask_payloads else None,
                process_outputs=mask_outputs if settings.langsmith_mask_payloads else None,
            )(func)
            return await traced(*args, **kwargs)
        return wrapper
    return decorator
```

NOTE (verify-on-implement): confirm against the installed `langsmith` package that `traceable` accepts `process_inputs`/`process_outputs` (current docs name them so). If the installed version uses different kwarg names (older releases used client-level `hide_inputs`/`hide_outputs`), adapt the wrapper but keep the test contract: masking functions applied when `langsmith_mask_payloads` is True.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && python -m pytest tests/unit/test_langsmith_tracing.py -v`
Expected: 3 PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/ai_driven/observability/langsmith_tracing.py backend/tests/unit/test_langsmith_tracing.py
git commit -m "feat(observability): langsmith tracing module with PII masking, inert by default"
```

### Task 3: Apply tracing to both providers

**Files:**
- Modify: `backend/src/ai_driven/claude/provider.py` — `AnthropicProvider.create_message` (~line 196) and `BedrockProvider.create_message` (~line 391)
- Test: `backend/tests/unit/test_provider_tracing.py`

- [ ] **Step 1: Write the failing test**

```python
"""backend/tests/unit/test_provider_tracing.py"""
from ai_driven.claude.provider import AnthropicProvider, BedrockProvider


def test_create_message_is_wrapped_with_tracing():
    # The traced_llm decorator preserves the wrapped function via functools.wraps
    # and marks it; assert the marker so a refactor can't silently drop tracing.
    assert getattr(AnthropicProvider.create_message, "__wrapped__", None) is not None
    assert getattr(BedrockProvider.create_message, "__wrapped__", None) is not None
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/unit/test_provider_tracing.py -v`
Expected: FAIL — `__wrapped__` is None (methods not decorated)

- [ ] **Step 3: Decorate the two methods**

In `backend/src/ai_driven/claude/provider.py`, add the import near the top:

```python
from ai_driven.observability.langsmith_tracing import traced_llm
```

Then decorate (signature and body unchanged):

```python
    @traced_llm(name="anthropic.create_message")
    async def create_message(   # AnthropicProvider
```

```python
    @traced_llm(name="bedrock.create_message")
    async def create_message(   # BedrockProvider
```

Do NOT decorate `create_message_stream` (async generators need different handling; streaming traces are Phase 2 scope) and do NOT decorate `ResilientProvider` (it delegates — decorating both would double-trace).

- [ ] **Step 4: Run the new test AND the full unit suite (zero-behavior-change gate)**

Run: `cd backend && python -m pytest tests/unit -x -q 2>&1 | tail -5`
Expected: all pass, no new failures vs. `git stash && python -m pytest tests/unit -q | tail -1 && git stash pop` baseline

- [ ] **Step 5: Commit**

```bash
git add backend/src/ai_driven/claude/provider.py backend/tests/unit/test_provider_tracing.py
git commit -m "feat(observability): trace provider create_message calls via langsmith (off by default)"
```

### Task 4: Phase 0 verification gate

- [ ] **Step 1: Full test suite**

Run: `cd backend && python -m pytest tests/unit tests/integration -q > /tmp/phase0-tests.log 2>&1; tail -3 /tmp/phase0-tests.log`
Expected: same pass/fail profile as pre-change baseline (record baseline first).

- [ ] **Step 2: Lint + types**

Run: `cd backend && ruff check src/ai_driven/observability/langsmith_tracing.py src/ai_driven/config.py && mypy src/ai_driven/observability/langsmith_tracing.py`
Expected: clean

- [ ] **Step 3: Runtime smoke — tracing disabled (default)**

Run: `cd backend && python -c "
from ai_driven.observability.langsmith_tracing import _tracing_active
assert _tracing_active() is False, 'tracing must be OFF by default'
print('OK: tracing inert by default')"`
Expected: `OK: tracing inert by default`

- [ ] **Step 4: Commit any fixes, then tag the phase**

```bash
git commit -am "chore(observability): phase 0 verification fixes" || true
```

---

## Phase 1 — LangChain model layer behind the existing provider interface

### Task 5: Dependencies + provider-impl flag

**Files:**
- Modify: `backend/pyproject.toml`
- Modify: `backend/src/ai_driven/config.py`
- Test: `backend/tests/unit/test_config_provider_impl.py`

- [ ] **Step 1: Write the failing test**

```python
"""backend/tests/unit/test_config_provider_impl.py"""
import pytest
from pydantic import ValidationError

from ai_driven.config import Settings


def test_provider_impl_defaults_to_legacy():
    assert Settings(_env_file=None).claude_provider_impl == "legacy"


def test_provider_impl_accepts_langchain():
    assert Settings(_env_file=None, claude_provider_impl="langchain").claude_provider_impl == "langchain"


def test_provider_impl_rejects_unknown():
    with pytest.raises(ValidationError):
        Settings(_env_file=None, claude_provider_impl="autogen")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/unit/test_config_provider_impl.py -v`
Expected: FAIL — no attribute `claude_provider_impl`

- [ ] **Step 3: Add the setting and deps**

`backend/src/ai_driven/config.py`, in the Claude block (uses `Literal` — add `from typing import Literal` to imports if absent):

```python
    # Phase 1 of docs/design/langgraph-migration.md: which implementation
    # backs create_provider(). "legacy" = the hand-rolled providers in
    # claude/provider.py; "langchain" = LangChainProvider. The provider
    # TYPE (anthropic vs bedrock) stays claude_provider — this flag only
    # selects the implementation behind the same interface.
    claude_provider_impl: Literal["legacy", "langchain"] = "legacy"
```

`backend/pyproject.toml`, after `"langsmith>=0.4",`:

```toml
  "langchain-core>=1.0",
  "langchain-anthropic>=1.1",
  "langchain-aws>=1.0",
```

NOTE (verify-on-implement): pin floors to the latest stable majors at implementation time (`pip index versions langchain-anthropic`). `langchain-anthropic>=1.1` is required for `method="json_schema"` structured output later (design §2.6); keep that floor even if a newer major exists.

- [ ] **Step 4: Install and verify**

Run: `cd backend && pip install -e . -q && python -m pytest tests/unit/test_config_provider_impl.py -v`
Expected: 3 PASS

- [ ] **Step 5: Commit**

```bash
git add backend/pyproject.toml backend/src/ai_driven/config.py backend/tests/unit/test_config_provider_impl.py
git commit -m "feat(claude): add langchain deps and claude_provider_impl flag (legacy default)"
```

### Task 6: Message conversion — Anthropic dicts → LangChain messages

**Files:**
- Create: `backend/src/ai_driven/claude/langchain_messages.py`
- Test: `backend/tests/unit/test_langchain_messages.py`

The rest of the codebase speaks Anthropic-format dicts (`{"role": ..., "content": str | [blocks]}`), including `tool_use` / `tool_result` / `thinking` blocks and assistant turns replayed from `raw_content`. The converter maps those to `langchain_core.messages` objects. Critical invariant (design §5 Phase 1a): assistant content blocks — including thinking blocks **with signatures** — must round-trip byte-identically, because multi-turn tool use under extended thinking requires replaying the signed thinking block.

- [ ] **Step 1: Write the failing tests**

```python
"""backend/tests/unit/test_langchain_messages.py"""
from langchain_core.messages import AIMessage, HumanMessage

from ai_driven.claude.langchain_messages import to_langchain_messages


def test_plain_user_string():
    out = to_langchain_messages([{"role": "user", "content": "hello"}])
    assert out == [HumanMessage(content="hello")]


def test_assistant_blocks_pass_through_verbatim():
    blocks = [
        {"type": "thinking", "thinking": "hmm", "signature": "sig-abc123"},
        {"type": "text", "text": "I'll check the repo."},
        {"type": "tool_use", "id": "toolu_1", "name": "jira.get_issue",
         "input": {"key": "KAN-1"}},
    ]
    out = to_langchain_messages([{"role": "assistant", "content": blocks}])
    assert isinstance(out[0], AIMessage)
    assert out[0].content == blocks          # verbatim — signature preserved


def test_tool_result_user_turn_passes_through():
    content = [{"type": "tool_result", "tool_use_id": "toolu_1",
                "content": "issue body"}]
    out = to_langchain_messages([{"role": "user", "content": content}])
    assert isinstance(out[0], HumanMessage)
    assert out[0].content == content


def test_mixed_conversation_order_preserved():
    msgs = [
        {"role": "user", "content": "do the task"},
        {"role": "assistant", "content": [{"type": "text", "text": "ok"}]},
        {"role": "user", "content": [{"type": "tool_result",
                                      "tool_use_id": "t1", "content": "r"}]},
    ]
    out = to_langchain_messages(msgs)
    assert [type(m) for m in out] == [HumanMessage, AIMessage, HumanMessage]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && python -m pytest tests/unit/test_langchain_messages.py -v`
Expected: FAIL — module not found

- [ ] **Step 3: Implement**

```python
"""backend/src/ai_driven/claude/langchain_messages.py

Anthropic-format message dicts → langchain_core messages.

Strategy: PASS CONTENT BLOCKS THROUGH VERBATIM. langchain-anthropic
forwards dict content blocks (text / tool_use / tool_result / thinking)
to the API unchanged, which preserves thinking-block signatures — the
load-bearing requirement from docs/design/langgraph-migration.md §5
Phase 1. We deliberately do NOT convert tool_result turns into
ToolMessage objects: a verbatim HumanMessage carrying tool_result
blocks is the representation closest to the wire format we replay today
via raw_content (client.py:164).
"""

from __future__ import annotations

from typing import Any

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage


def to_langchain_messages(messages: list[dict[str, Any]]) -> list[BaseMessage]:
    out: list[BaseMessage] = []
    for m in messages:
        role = m.get("role")
        content = m.get("content")
        if role == "assistant":
            out.append(AIMessage(content=content))
        elif role == "user":
            out.append(HumanMessage(content=content))
        else:
            raise ValueError(f"unsupported role for Anthropic conversation: {role!r}")
    return out
```

NOTE (verify-on-implement): write one throwaway script against the REAL `langchain-anthropic` (no API call — inspect `ChatAnthropic._get_request_payload(...)` output) confirming that verbatim dict blocks survive into the request payload with `signature` intact. If the installed version normalizes content blocks, adjust `to_langchain_messages` to whatever representation round-trips, and update the design doc's §7 claim list. The test contract stays: signature preserved in the outgoing payload.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && python -m pytest tests/unit/test_langchain_messages.py -v`
Expected: 4 PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/ai_driven/claude/langchain_messages.py backend/tests/unit/test_langchain_messages.py
git commit -m "feat(claude): anthropic-dict to langchain message conversion (verbatim blocks)"
```

### Task 7: Response parsing — AIMessage → MessageResponse

**Files:**
- Create: `backend/src/ai_driven/claude/langchain_response.py`
- Test: `backend/tests/unit/test_langchain_response.py`

- [ ] **Step 1: Write the failing tests**

```python
"""backend/tests/unit/test_langchain_response.py"""
from langchain_core.messages import AIMessage

from ai_driven.claude.langchain_response import parse_ai_message
from ai_driven.claude.provider import TextBlock, ThinkingBlock, ToolUseBlock


def _ai_message(content, stop_reason="end_turn", usage=None):
    return AIMessage(
        content=content,
        response_metadata={"stop_reason": stop_reason, "model": "claude-sonnet-4-6"},
        usage_metadata=usage or {
            "input_tokens": 100, "output_tokens": 20, "total_tokens": 120,
            "input_token_details": {"cache_read": 80, "cache_creation": 10},
        },
    )


def test_text_only():
    r = parse_ai_message(_ai_message([{"type": "text", "text": "hi"}]),
                         requested_model="claude-sonnet-4-6")
    assert r.content == [TextBlock(text="hi")]
    assert r.stop_reason == "end_turn"
    assert r.usage.input_tokens == 100
    assert r.usage.output_tokens == 20
    assert r.usage.cache_read_input_tokens == 80
    assert r.usage.cache_creation_input_tokens == 10


def test_tool_use_sets_blocks_and_raw_content():
    blocks = [
        {"type": "thinking", "thinking": "...", "signature": "sig-1"},
        {"type": "tool_use", "id": "t1", "name": "jira.get_issue",
         "input": {"key": "KAN-1"}},
    ]
    r = parse_ai_message(_ai_message(blocks, stop_reason="tool_use"),
                         requested_model="claude-sonnet-4-6")
    assert r.has_tool_use
    assert r.tool_use_blocks == [ToolUseBlock(id="t1", name="jira.get_issue",
                                              input={"key": "KAN-1"})]
    assert any(isinstance(b, ThinkingBlock) for b in r.content)
    # raw_content is the replay carrier (design §5 Phase 1a): must be the
    # verbatim block dicts INCLUDING the thinking signature.
    assert r.raw_content == blocks


def test_string_content_normalized_to_text_block():
    r = parse_ai_message(_ai_message("plain string"), requested_model="m")
    assert r.content == [TextBlock(text="plain string")]
    assert r.raw_content == [{"type": "text", "text": "plain string"}]


def test_missing_usage_degrades_to_zero():
    msg = AIMessage(content="x", response_metadata={"stop_reason": "end_turn"})
    r = parse_ai_message(msg, requested_model="m")
    assert r.usage.input_tokens == 0
    assert r.model == "m"   # falls back to requested model
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && python -m pytest tests/unit/test_langchain_response.py -v`
Expected: FAIL — module not found

- [ ] **Step 3: Implement**

```python
"""backend/src/ai_driven/claude/langchain_response.py

AIMessage → MessageResponse. raw_content carries the verbatim Anthropic
content-block dicts so client.py:164's assistant-turn replay (and the
thinking-signature requirement) keeps working identically.
"""

from __future__ import annotations

from typing import Any

from langchain_core.messages import AIMessage

from ai_driven.claude.provider import (
    ContentBlock,
    MessageResponse,
    TextBlock,
    ThinkingBlock,
    TokenUsage,
    ToolUseBlock,
)


def parse_ai_message(msg: AIMessage, *, requested_model: str) -> MessageResponse:
    raw: list[dict[str, Any]]
    if isinstance(msg.content, str):
        raw = [{"type": "text", "text": msg.content}] if msg.content else []
    else:
        raw = [b for b in msg.content if isinstance(b, dict)]

    content: list[ContentBlock] = []
    for block in raw:
        btype = block.get("type")
        if btype == "text":
            content.append(TextBlock(text=block.get("text", "")))
        elif btype == "tool_use":
            content.append(ToolUseBlock(id=block["id"], name=block["name"],
                                        input=block.get("input", {})))
        elif btype == "thinking":
            content.append(ThinkingBlock(thinking=block.get("thinking", "")))
        # unknown block types stay in raw_content but are not surfaced

    usage_md = msg.usage_metadata or {}
    details = usage_md.get("input_token_details") or {}
    usage = TokenUsage(
        input_tokens=usage_md.get("input_tokens", 0),
        output_tokens=usage_md.get("output_tokens", 0),
        cache_creation_input_tokens=details.get("cache_creation", 0) or 0,
        cache_read_input_tokens=details.get("cache_read", 0) or 0,
    )

    meta = msg.response_metadata or {}
    return MessageResponse(
        content=content,
        stop_reason=meta.get("stop_reason", "end_turn"),
        usage=usage,
        model=meta.get("model") or meta.get("model_name") or requested_model,
        raw_content=raw,
    )
```

NOTE (verify-on-implement): confirm field names on the installed packages — `usage_metadata["input_token_details"]["cache_read"/"cache_creation"]` (langchain-core standard) and `response_metadata["stop_reason"]` for both `ChatAnthropic` and `ChatBedrockConverse`. If Bedrock surfaces stop reason differently (e.g. `stopReason`), normalize HERE, not in callers.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && python -m pytest tests/unit/test_langchain_response.py -v`
Expected: 4 PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/ai_driven/claude/langchain_response.py backend/tests/unit/test_langchain_response.py
git commit -m "feat(claude): AIMessage to MessageResponse parser with raw-content carrier"
```

### Task 8: LangChainProvider — non-streaming create_message

**Files:**
- Create: `backend/src/ai_driven/claude/langchain_provider.py`
- Test: `backend/tests/unit/test_langchain_provider.py`

- [ ] **Step 1: Write the failing tests**

```python
"""backend/tests/unit/test_langchain_provider.py"""
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from langchain_core.messages import AIMessage

from ai_driven.claude.langchain_provider import LangChainProvider
from ai_driven.config import ClaudeProviderType


def _fake_chat_model(reply: AIMessage):
    model = MagicMock()
    model.ainvoke = AsyncMock(return_value=reply)
    bound = MagicMock()
    bound.ainvoke = AsyncMock(return_value=reply)
    model.bind_tools = MagicMock(return_value=bound)
    return model


@pytest.mark.asyncio
async def test_create_message_returns_parsed_response():
    reply = AIMessage(
        content=[{"type": "text", "text": "done"}],
        response_metadata={"stop_reason": "end_turn", "model": "claude-sonnet-4-6"},
        usage_metadata={"input_tokens": 5, "output_tokens": 2, "total_tokens": 7},
    )
    provider = LangChainProvider(ClaudeProviderType.ANTHROPIC)
    with patch.object(provider, "_build_chat_model",
                      return_value=_fake_chat_model(reply)) as build:
        resp = await provider.create_message(
            model="claude-sonnet-4-6", max_tokens=1024,
            messages=[{"role": "user", "content": "go"}],
            system="sys", temperature=0.5,
        )
    assert resp.text == "done"
    assert resp.usage.input_tokens == 5
    build.assert_called_once()
    kwargs = build.call_args.kwargs
    assert kwargs["model"] == "claude-sonnet-4-6"
    assert kwargs["max_tokens"] == 1024
    assert kwargs["temperature"] == 0.5


@pytest.mark.asyncio
async def test_tools_are_bound_with_anthropic_schemas():
    reply = AIMessage(content="ok", response_metadata={}, usage_metadata=None)
    fake = _fake_chat_model(reply)
    provider = LangChainProvider(ClaudeProviderType.ANTHROPIC)
    tools = [{"name": "jira.get_issue", "description": "d",
              "input_schema": {"type": "object", "properties": {}}}]
    with patch.object(provider, "_build_chat_model", return_value=fake):
        await provider.create_message(
            model="m", max_tokens=10,
            messages=[{"role": "user", "content": "go"}], tools=tools,
        )
    fake.bind_tools.assert_called_once()
    bound_tools = fake.bind_tools.call_args.args[0]
    assert bound_tools[0]["name"] == "jira.get_issue"


@pytest.mark.asyncio
async def test_system_prompt_prepended_as_system_message():
    reply = AIMessage(content="ok", response_metadata={}, usage_metadata=None)
    fake = _fake_chat_model(reply)
    provider = LangChainProvider(ClaudeProviderType.ANTHROPIC)
    with patch.object(provider, "_build_chat_model", return_value=fake):
        await provider.create_message(
            model="m", max_tokens=10, system="be terse",
            messages=[{"role": "user", "content": "go"}],
        )
    sent = fake.ainvoke.call_args.args[0]
    from langchain_core.messages import SystemMessage
    assert isinstance(sent[0], SystemMessage)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && python -m pytest tests/unit/test_langchain_provider.py -v`
Expected: FAIL — module not found

- [ ] **Step 3: Implement**

```python
"""backend/src/ai_driven/claude/langchain_provider.py

LangChain-backed implementation of the ClaudeProvider interface —
Phase 1 of docs/design/langgraph-migration.md. Selected by
settings.claude_provider_impl == "langchain"; the legacy providers in
provider.py remain the default. Behavior contract: byte-equivalent
requests and equivalent MessageResponse objects vs. the legacy
implementation, verified by the Phase 1 shadow-parity gate.
"""

from __future__ import annotations

import logging
from typing import Any, AsyncIterator

from langchain_core.messages import SystemMessage

from ai_driven.claude.langchain_messages import to_langchain_messages
from ai_driven.claude.langchain_response import parse_ai_message
from ai_driven.claude.provider import (
    ClaudeProvider,
    MessageResponse,
    _with_cache_control_messages,
    _with_cache_control_system,
    _with_cache_control_tools,
)
from ai_driven.config import ClaudeProviderType, get_settings
from ai_driven.observability.langsmith_tracing import traced_llm
from ai_driven.observability.metrics import emit_claude_metrics

logger = logging.getLogger(__name__)


class LangChainProvider(ClaudeProvider):
    """ChatAnthropic / ChatBedrockConverse behind the ClaudeProvider interface."""

    def __init__(self, provider_type: ClaudeProviderType) -> None:
        self._provider_type = provider_type

    # --- chat model construction (one per call: model params vary per call) ---

    def _build_chat_model(self, *, model: str, max_tokens: int,
                          temperature: float,
                          thinking: dict[str, Any] | None,
                          stop_sequences: list[str] | None) -> Any:
        if self._provider_type == ClaudeProviderType.ANTHROPIC:
            from langchain_anthropic import ChatAnthropic
            settings = get_settings()
            kwargs: dict[str, Any] = {
                "model": model,
                "max_tokens": max_tokens,
                "temperature": temperature,
                "api_key": settings.anthropic_api_key,
            }
            if thinking:
                kwargs["thinking"] = thinking
            if stop_sequences:
                kwargs["stop_sequences"] = stop_sequences
            return ChatAnthropic(**kwargs)

        # Bedrock
        from botocore.config import Config
        from langchain_aws import ChatBedrockConverse

        from ai_driven.claude.provider import BedrockProvider
        settings = get_settings()
        kwargs = {
            "model": BedrockProvider._resolve_model_id(model),
            "max_tokens": max_tokens,
            "temperature": temperature,
            "region_name": settings.aws_region,
            # Same timeout posture as the legacy provider (commit 9b2423f):
            # 300s read for long agentic iterations, fast connect failure.
            "config": Config(read_timeout=300, connect_timeout=10,
                             retries={"max_attempts": 2, "mode": "standard"}),
        }
        if settings.aws_access_key_id and settings.aws_secret_access_key:
            kwargs["aws_access_key_id"] = settings.aws_access_key_id
            kwargs["aws_secret_access_key"] = settings.aws_secret_access_key
            if settings.aws_session_token:
                kwargs["aws_session_token"] = settings.aws_session_token
        if thinking:
            kwargs["additional_model_request_fields"] = {"thinking": thinking}
        if stop_sequences:
            kwargs["stop_sequences"] = stop_sequences
        return ChatBedrockConverse(**kwargs)

    # --- ClaudeProvider interface ---

    @traced_llm(name="langchain.create_message")
    async def create_message(
        self,
        *,
        model: str,
        max_tokens: int,
        messages: list[dict[str, Any]],
        system: str | None = None,
        tools: list[dict[str, Any]] | None = None,
        temperature: float = 1.0,
        thinking: dict[str, Any] | None = None,
        stop_sequences: list[str] | None = None,
    ) -> MessageResponse:
        chat = self._build_chat_model(
            model=model, max_tokens=max_tokens, temperature=temperature,
            thinking=thinking, stop_sequences=stop_sequences,
        )
        if tools:
            # Anthropic-format tool schemas pass through bind_tools verbatim
            # for ChatAnthropic; for Bedrock the dotted-name shim is applied
            # (Converse toolName regex forbids dots — provider.py:322-336).
            if self._provider_type == ClaudeProviderType.BEDROCK:
                from ai_driven.claude.provider import BedrockProvider
                tools = BedrockProvider._sanitize_tools_for_bedrock(tools)
            chat = chat.bind_tools(_with_cache_control_tools(tools))

        lc_messages = to_langchain_messages(
            _with_cache_control_messages(
                BedrockProvider._sanitize_messages_for_bedrock(messages)
                if self._provider_type == ClaudeProviderType.BEDROCK
                else messages
            )
        )
        if system:
            sys_content = _with_cache_control_system(system)
            lc_messages.insert(0, SystemMessage(content=sys_content))

        reply = await chat.ainvoke(lc_messages)
        result = parse_ai_message(reply, requested_model=model)

        if self._provider_type == ClaudeProviderType.BEDROCK:
            from ai_driven.claude.provider import BedrockProvider as _BP
            for block in result.tool_use_blocks:
                block.name = _BP._decode_tool_name(block.name)
            for raw in result.raw_content:
                if isinstance(raw, dict) and raw.get("type") == "tool_use" and "name" in raw:
                    raw["name"] = _BP._decode_tool_name(raw["name"])

        emit_claude_metrics(result.usage, model,
                            f"langchain-{self._provider_type.value}")
        return result

    async def create_message_stream(
        self,
        *,
        model: str,
        max_tokens: int,
        messages: list[dict[str, Any]],
        system: str | None = None,
        tools: list[dict[str, Any]] | None = None,
        temperature: float = 1.0,
        thinking: dict[str, Any] | None = None,
        stop_sequences: list[str] | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        """Streaming: emit Bedrock-style dict chunks for text deltas plus the
        _final_message sentinel — the exact contract client.py:_stream_iteration
        consumes (it handles dict-shaped chunks on the `chunk_type ==
        "content_block_delta"` branch)."""
        chat = self._build_chat_model(
            model=model, max_tokens=max_tokens, temperature=temperature,
            thinking=thinking, stop_sequences=stop_sequences,
        )
        if tools:
            if self._provider_type == ClaudeProviderType.BEDROCK:
                from ai_driven.claude.provider import BedrockProvider
                tools = BedrockProvider._sanitize_tools_for_bedrock(tools)
            chat = chat.bind_tools(_with_cache_control_tools(tools))

        lc_messages = to_langchain_messages(
            _with_cache_control_messages(
                BedrockProvider._sanitize_messages_for_bedrock(messages)
                if self._provider_type == ClaudeProviderType.BEDROCK
                else messages
            )
        )
        if system:
            lc_messages.insert(0, SystemMessage(content=_with_cache_control_system(system)))

        final_chunk = None
        async for chunk in chat.astream(lc_messages):
            final_chunk = chunk if final_chunk is None else final_chunk + chunk
            text = _chunk_text(chunk)
            if text:
                yield {"type": "content_block_delta",
                       "delta": {"type": "text_delta", "text": text}}

        if final_chunk is not None:
            from langchain_core.messages import AIMessage
            final_msg = AIMessage(
                content=final_chunk.content,
                response_metadata=getattr(final_chunk, "response_metadata", {}) or {},
                usage_metadata=getattr(final_chunk, "usage_metadata", None),
            )
            result = parse_ai_message(final_msg, requested_model=model)
            if self._provider_type == ClaudeProviderType.BEDROCK:
                from ai_driven.claude.provider import BedrockProvider as _BP
                for block in result.tool_use_blocks:
                    block.name = _BP._decode_tool_name(block.name)
            emit_claude_metrics(result.usage, model,
                                f"langchain-{self._provider_type.value}")
            yield {"type": "_final_message", "data": result}


def _chunk_text(chunk: Any) -> str:
    """Extract text delta from an AIMessageChunk (string or block-list content)."""
    content = getattr(chunk, "content", "")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            b.get("text", "") for b in content
            if isinstance(b, dict) and b.get("type") in ("text", "text_delta")
        )
    return ""


# Import placed at module level for the Bedrock sanitize calls above.
from ai_driven.claude.provider import BedrockProvider  # noqa: E402
```

NOTE (verify-on-implement): (1) `ChatAnthropic` constructor kwarg for thinking — verify name (`thinking` vs `model_kwargs={"thinking": ...}`) on the installed version. (2) `bind_tools` with raw Anthropic-format dict schemas — verify pass-through; if it requires conversion, convert from our `input_schema` format. (3) Chunk accumulation via `+` on AIMessageChunk is langchain-core standard — verify `usage_metadata` survives accumulation. (4) The module-level `BedrockProvider` import at the bottom is intentional to avoid a circular import at module top — if no cycle exists, move it to the top imports.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && python -m pytest tests/unit/test_langchain_provider.py -v`
Expected: 3 PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/ai_driven/claude/langchain_provider.py backend/tests/unit/test_langchain_provider.py
git commit -m "feat(claude): LangChainProvider implementing ClaudeProvider via ChatAnthropic/ChatBedrockConverse"
```

### Task 9: Factory wiring

**Files:**
- Modify: `backend/src/ai_driven/claude/provider.py` — `create_provider()` (~line 686)
- Test: `backend/tests/unit/test_create_provider_impl.py`

- [ ] **Step 1: Write the failing tests**

```python
"""backend/tests/unit/test_create_provider_impl.py"""
from unittest.mock import patch

from ai_driven.claude.langchain_provider import LangChainProvider
from ai_driven.claude.provider import (
    AnthropicProvider,
    ResilientProvider,
    create_provider,
)
from ai_driven.config import Settings, get_settings


def _settings(**over):
    s = Settings(_env_file=None, anthropic_api_key="test-key", **over)
    return s


def test_legacy_default_unchanged():
    with patch("ai_driven.claude.provider.get_settings", return_value=_settings()):
        p = create_provider(resilient=False)
    assert isinstance(p, AnthropicProvider)


def test_langchain_impl_selected_by_flag():
    with patch("ai_driven.claude.provider.get_settings",
               return_value=_settings(claude_provider_impl="langchain")):
        p = create_provider(resilient=False)
    assert isinstance(p, LangChainProvider)


def test_langchain_impl_still_wrapped_resilient():
    with patch("ai_driven.claude.provider.get_settings",
               return_value=_settings(claude_provider_impl="langchain")):
        p = create_provider(resilient=True)
    assert isinstance(p, ResilientProvider)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && python -m pytest tests/unit/test_create_provider_impl.py -v`
Expected: FAIL — `LangChainProvider` never returned

- [ ] **Step 3: Modify `create_provider`**

Replace the body of `create_provider` in `backend/src/ai_driven/claude/provider.py`:

```python
def create_provider(
    provider_type: ClaudeProviderType | None = None,
    resilient: bool = True,
) -> ClaudeProvider:
    """Factory function to create the configured Claude provider.

    Always returns a real provider (Anthropic-direct or Bedrock). If the
    underlying credentials are missing or invalid, the FIRST real API call
    will raise — that is intentional. We do not synthesize mock responses
    in any environment. A visible failure is always preferable to a silent
    one, especially in debug runs where a false-positive pipeline can leak
    fictional data into Jira / GitHub.

    claude_provider_impl selects the implementation behind the interface:
    "legacy" (default) = the hand-rolled providers in this module;
    "langchain" = LangChainProvider (Phase 1, docs/design/langgraph-migration.md).
    """
    settings = get_settings()
    if provider_type is None:
        provider_type = settings.claude_provider

    if settings.claude_provider_impl == "langchain":
        from ai_driven.claude.langchain_provider import LangChainProvider
        provider: ClaudeProvider = LangChainProvider(provider_type)
    else:
        providers = {
            ClaudeProviderType.ANTHROPIC: AnthropicProvider,
            ClaudeProviderType.BEDROCK: BedrockProvider,
        }
        provider = providers[provider_type]()

    if resilient:
        return ResilientProvider(provider)
    return provider
```

- [ ] **Step 4: Run the new tests AND the full unit suite**

Run: `cd backend && python -m pytest tests/unit -q 2>&1 | tail -3`
Expected: all pass; legacy default means zero behavior change for every existing test

- [ ] **Step 5: Commit**

```bash
git add backend/src/ai_driven/claude/provider.py backend/tests/unit/test_create_provider_impl.py
git commit -m "feat(claude): create_provider honors claude_provider_impl flag, legacy default"
```

### Task 10: Phase 1 verification + parity smoke harness

**Files:**
- Create: `backend/scripts/phase1_impl_parity.py`
- Test: full suite + lint + types

- [ ] **Step 1: Write the parity smoke script**

```python
"""backend/scripts/phase1_impl_parity.py

One-shot parity smoke: send the same 3 requests through legacy and
langchain impls, diff the MessageResponse surface the rest of the
codebase consumes. NOT a unit test — requires real credentials; run
manually / in the shadow environment. Usage:

    AI_DRIVEN_CLAUDE_PROVIDER=anthropic python scripts/phase1_impl_parity.py
"""

from __future__ import annotations

import asyncio
import json

CASES = [
    {"label": "plain", "messages": [{"role": "user", "content": "Reply with exactly: pong"}],
     "kwargs": {}},
    {"label": "system+temp", "messages": [{"role": "user", "content": "One word: colour of the sky at noon."}],
     "kwargs": {"system": "Answer in one lowercase word.", "temperature": 0.0}},
    {"label": "tool_use", "messages": [{"role": "user", "content": "Look up ticket KAN-1 using the tool."}],
     "kwargs": {"tools": [{"name": "jira.get_issue", "description": "Fetch a Jira issue by key.",
                           "input_schema": {"type": "object",
                                            "properties": {"key": {"type": "string"}},
                                            "required": ["key"]}}]}},
]


async def run(impl: str) -> list[dict]:
    from ai_driven.config import get_settings
    get_settings.cache_clear()
    import os
    os.environ["AI_DRIVEN_CLAUDE_PROVIDER_IMPL"] = impl
    get_settings.cache_clear()

    from ai_driven.claude.provider import create_provider
    provider = create_provider(resilient=False)
    out = []
    for case in CASES:
        r = await provider.create_message(
            model=get_settings().claude_default_model, max_tokens=256,
            messages=[dict(m) for m in case["messages"]], **case["kwargs"],
        )
        out.append({
            "label": case["label"],
            "stop_reason": r.stop_reason,
            "has_tool_use": r.has_tool_use,
            "tool_names": [b.name for b in r.tool_use_blocks],
            "usage_nonzero": r.usage.input_tokens > 0 and r.usage.output_tokens > 0,
            "text_nonempty": bool(r.text.strip()) or r.has_tool_use,
        })
    return out


async def main() -> None:
    legacy = await run("legacy")
    langchain = await run("langchain")
    failures = []
    for a, b in zip(legacy, langchain):
        for key in ("stop_reason", "has_tool_use", "tool_names", "usage_nonzero", "text_nonempty"):
            if a[key] != b[key]:
                failures.append(f"{a['label']}.{key}: legacy={a[key]!r} langchain={b[key]!r}")
    print(json.dumps({"legacy": legacy, "langchain": langchain}, indent=2))
    if failures:
        raise SystemExit("PARITY FAIL:\n" + "\n".join(failures))
    print("PARITY OK (3/3 cases)")


if __name__ == "__main__":
    asyncio.run(main())
```

NOTE: settings env prefix — check `Settings.model_config` for the actual env prefix (`AI_DRIVEN_` assumed from `AI_DRIVEN_SQL_ECHO` in config.py comments); adjust the env var name if different.

- [ ] **Step 2: Full suite + lint + types**

Run: `cd backend && python -m pytest tests/unit -q > /tmp/phase1-tests.log 2>&1; tail -3 /tmp/phase1-tests.log && ruff check src/ai_driven/claude/ && mypy src/ai_driven/claude/langchain_provider.py src/ai_driven/claude/langchain_messages.py src/ai_driven/claude/langchain_response.py`
Expected: tests pass, ruff clean, mypy clean

- [ ] **Step 3: Run the parity smoke against real credentials (if available in this environment; otherwise mark as deploy-gate)**

Run: `cd backend && python scripts/phase1_impl_parity.py`
Expected: `PARITY OK (3/3 cases)` — if no credentials available, record "deferred to shadow environment" in the task return; the Phase 1 exit gate (1 week shadow parity, design §5) owns final sign-off.

- [ ] **Step 4: Commit**

```bash
git add backend/scripts/phase1_impl_parity.py
git commit -m "feat(claude): phase 1 impl parity smoke harness"
```

---

## Self-review record

- **Spec coverage:** Phase 0 = design §5 Phase 0 (langsmith dep, traceable wrappers, zero behavior change) → Tasks 1–4. Phase 1 = design §5 Phase 1 (deps, LangChainProvider behind flag, thinking signature carrier, Bedrock timeout/shim, refusal stop_reason via response parsing, cost via emit_claude_metrics) → Tasks 5–10. The 1-week shadow-parity exit gate and IAM Converse verification are deploy-time activities outside this plan's code scope — recorded in Task 10 Step 3 and the design doc.
- **Placeholder scan:** all code complete; "verify-on-implement" notes are library-version checks with explicit fallback instructions, not deferred work.
- **Type consistency:** `LangChainProvider(provider_type: ClaudeProviderType)`; `parse_ai_message(msg, *, requested_model)`; `to_langchain_messages(messages) -> list[BaseMessage]` — used consistently across Tasks 6–9.
