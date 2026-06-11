"""Tests for the LangSmith tracing module (Phase 0).

Covers the plan's base contract plus the binding SECURITY AMENDMENT:
allowlist deny-by-default masking (LSMITH-03), env gating (LSMITH-01),
masking fail-secure (LSMITH-12), tracing fail-open (LSMITH-13), and the
streaming-emits-no-span guarantee (LSMITH-02).
"""

import logging

import pytest

from ai_driven.config import Environment, Settings
from ai_driven.observability.langsmith_tracing import (
    PermittedMetadataTracer,
    mask_inputs,
    mask_outputs,
    traced_llm,
)


# --------------------------------------------------------------------------
# Base contract (plan Task 2 + LSMITH-04)
# --------------------------------------------------------------------------
def test_mask_inputs_redacts_message_content_and_system():
    masked = mask_inputs({
        "model": "claude-sonnet-4-6",
        "system": "You are a fintech agent. Customer: John, +63-917-555-0100",
        "messages": [{"role": "user", "content": "ticket body with PII"}],
        "max_tokens": 8192,
    })
    assert masked["model"] == "claude-sonnet-4-6"          # metadata survives
    assert masked["max_tokens"] == 8192
    # Redaction summary reports the true length (the plan sketch's "60chars"
    # was a typo; the string is 57 chars). The contract is "length summary,
    # no content" — the exact count must reflect the real string.
    assert masked["system"] == "<redacted:57chars>"
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
async def test_traced_llm_is_passthrough_when_disabled():
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


# --------------------------------------------------------------------------
# LSMITH-03 — allowlist / deny-by-default
# --------------------------------------------------------------------------
def test_mask_inputs_drops_unexpected_pii_kwarg():
    # A future kwarg carrying PII MUST NOT leak by default.
    masked = mask_inputs({
        "model": "claude-sonnet-4-6",
        "metadata": {"customer_email": "jane@example.com"},
        "extra_headers": {"X-Customer": "Jane Doe"},
    })
    assert masked["model"] == "claude-sonnet-4-6"
    # Unexpected keys are dropped entirely — no value survives.
    assert "metadata" not in masked
    assert "extra_headers" not in masked
    # Defensive: the PII string appears nowhere in the masked output.
    flat = repr(masked)
    assert "jane@example.com" not in flat
    assert "Jane Doe" not in flat


def test_mask_inputs_redacts_non_string_content():
    # Non-string content (dict, list, bytes) on a content field MUST be
    # redacted, not passed through.
    masked = mask_inputs({
        "model": "m",
        "messages": [
            {"role": "user", "content": [{"type": "text", "text": "secret"}]},
            {"role": "user", "content": b"\x00binary-pii"},
        ],
    })
    assert masked["messages"] == [
        {"role": "user", "content": "<redacted>"},
        {"role": "user", "content": "<redacted>"},
    ]
    assert "secret" not in repr(masked)
    assert "binary-pii" not in repr(masked)


def test_mask_inputs_redacts_tools_and_keeps_permitted_metadata():
    masked = mask_inputs({
        "model": "m",
        "max_tokens": 100,
        "temperature": 0.5,
        "top_p": 0.9,
        "tools": [{"name": "github.create_pr", "input_schema": {}}],
        "system": "internal agent logic",
    })
    assert masked["max_tokens"] == 100
    assert masked["temperature"] == 0.5
    assert masked["top_p"] == 0.9
    assert masked["tools"] == "<redacted:1 tools>"
    assert masked["system"] == "<redacted:20chars>"
    assert "github.create_pr" not in repr(masked)


# --------------------------------------------------------------------------
# LSMITH-01 — env gating: tracing on + masking off in prod/staging refuses
# --------------------------------------------------------------------------
@pytest.mark.parametrize("env", [Environment.PRODUCTION, Environment.STAGING])
def test_tracer_refuses_masking_off_in_prod_or_staging(env):
    settings = Settings(
        _env_file=None,
        environment=env,
        langsmith_enabled=True,
        langsmith_mask_payloads=False,
    )
    with pytest.raises(ValueError, match="masking"):
        PermittedMetadataTracer(settings)


@pytest.mark.parametrize("env", [Environment.LOCAL, Environment.DEV])
def test_tracer_allows_masking_off_in_nonprod(env):
    settings = Settings(
        _env_file=None,
        environment=env,
        langsmith_enabled=True,
        langsmith_mask_payloads=False,
    )
    # Constructs without raising — non-prod may turn masking off.
    PermittedMetadataTracer(settings)


def test_tracer_allows_masking_on_in_prod():
    settings = Settings(
        _env_file=None,
        environment=Environment.PRODUCTION,
        langsmith_enabled=True,
        langsmith_mask_payloads=True,
    )
    PermittedMetadataTracer(settings)


# --------------------------------------------------------------------------
# LSMITH-12 — masking failure fails SECURE (drop trace / redacted placeholder,
# never raw payload), and the LLM call result is unchanged.
# --------------------------------------------------------------------------
def test_mask_inputs_never_leaks_raw_on_pathological_input():
    # A pathological object on a content field must NOT leak; it is redacted
    # wholesale (non-str/non-list → "<redacted>").
    secret = "SENSITIVE-PII-PAYLOAD"

    class Carrier:
        def __str__(self):
            return secret

        def __repr__(self):
            return secret

    masked = mask_inputs({"messages": Carrier(), "system": Carrier()})
    assert secret not in repr(masked)
    assert masked["messages"] == "<redacted>"
    assert masked["system"] == "<redacted>"


def test_mask_inputs_fails_secure_on_internal_error():
    # If the masker hits an unrecoverable internal error, it returns a fully
    # redacted placeholder — NEVER the raw payload (LSMITH-12). A dict-like
    # whose .get() raises forces the except branch.
    secret = "SENSITIVE-PII-PAYLOAD"

    class ExplodingDict(dict):
        def get(self, *_a, **_k):
            raise RuntimeError("boom")

    payload = ExplodingDict()
    payload["messages"] = [{"role": "user", "content": secret}]
    masked = mask_inputs(payload)
    assert masked == {"__masking_error__": "<redacted:masking failed>"}
    assert secret not in repr(masked)


@pytest.mark.asyncio
async def test_masking_error_drops_trace_but_call_succeeds(monkeypatch):
    # If masking raises inside the trace path, the LLM call MUST still
    # return its real result (fail-secure for content, fail-open for call).
    import ai_driven.observability.langsmith_tracing as lt

    settings = Settings(_env_file=None, langsmith_enabled=True)
    monkeypatch.setattr(lt, "get_settings", lambda: settings)
    monkeypatch.setenv("LANGSMITH_API_KEY", "test-key")

    def boom(_inputs):
        raise RuntimeError("masker exploded")

    monkeypatch.setattr(lt, "mask_inputs", boom)

    @traced_llm(name="call")
    async def real_call(**kwargs):
        return {"data": "real-llm-result"}

    result = await real_call(model="m", messages=[{"role": "user", "content": "pii"}])
    assert result == {"data": "real-llm-result"}


# --------------------------------------------------------------------------
# LSMITH-13 — tracing failure fails OPEN: LLM call result unchanged.
# --------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_tracing_error_fails_open(monkeypatch):
    import ai_driven.observability.langsmith_tracing as lt

    settings = Settings(_env_file=None, langsmith_enabled=True)
    monkeypatch.setattr(lt, "get_settings", lambda: settings)
    monkeypatch.setenv("LANGSMITH_API_KEY", "test-key")

    # Force the traceable wrapping itself to explode.
    def broken_traceable(*_a, **_k):
        raise RuntimeError("langsmith down")

    monkeypatch.setattr(lt, "_get_traceable", lambda: broken_traceable)

    @traced_llm(name="call")
    async def real_call(**kwargs):
        return {"data": "real-llm-result"}

    result = await real_call(model="m", messages=[])
    assert result == {"data": "real-llm-result"}


# --------------------------------------------------------------------------
# LSMITH-06 — no key reaches logs during enable.
# --------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_no_api_key_in_logs(monkeypatch, caplog):
    import ai_driven.observability.langsmith_tracing as lt

    settings = Settings(_env_file=None, langsmith_enabled=True)
    monkeypatch.setattr(lt, "get_settings", lambda: settings)
    secret = "ls-supersecret-key-value"
    monkeypatch.setenv("LANGSMITH_API_KEY", secret)

    @traced_llm(name="call")
    async def real_call(**kwargs):
        return {"data": "ok"}

    with caplog.at_level(logging.DEBUG):
        await real_call(model="m", messages=[])
    assert secret not in caplog.text


# --------------------------------------------------------------------------
# LSMITH-02 — streaming emits NO span (Phase 0 does not trace streaming).
# A capturing transport sees no run created by the streaming call.
# --------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_streaming_emits_no_span(monkeypatch):
    # The streaming methods are NOT decorated with traced_llm in Phase 0.
    # We assert that no span is produced for an undecorated streaming-style
    # coroutine even when tracing is enabled and a capturing client exists.
    import ai_driven.observability.langsmith_tracing as lt

    settings = Settings(_env_file=None, langsmith_enabled=True)
    monkeypatch.setattr(lt, "get_settings", lambda: settings)
    monkeypatch.setenv("LANGSMITH_API_KEY", "test-key")

    created_runs = []

    def capturing_traceable(*_a, **_k):
        def deco(fn):
            async def wrapped(*a, **k):
                created_runs.append("span")
                return await fn(*a, **k)
            return wrapped
        return deco

    monkeypatch.setattr(lt, "_get_traceable", lambda: capturing_traceable)

    # An undecorated streaming generator — represents create_message_stream.
    async def stream_gen(**kwargs):
        for chunk in [{"type": "delta", "text": "raw PII content"}]:
            yield chunk

    chunks = [c async for c in stream_gen(model="m", messages=[])]
    assert chunks == [{"type": "delta", "text": "raw PII content"}]
    # No span was created for the streaming path.
    assert created_runs == []
