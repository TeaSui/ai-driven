"""Tracing wiring on the concrete Claude providers (Phase 0, LSMITH-02/13).

Only the unary ``create_message`` is decorated. ``create_message_stream`` and
``ResilientProvider`` are intentionally left undecorated (no streaming egress
in Phase 0; tracing applied at exactly one layer to avoid double spans).
"""

from ai_driven.claude.provider import (
    AnthropicProvider,
    BedrockProvider,
    ResilientProvider,
)


def test_create_message_is_wrapped_with_tracing():
    # traced_llm uses functools.wraps, so the wrapped function is exposed via
    # __wrapped__. Asserting the marker prevents a refactor from silently
    # dropping tracing.
    assert getattr(AnthropicProvider.create_message, "__wrapped__", None) is not None
    assert getattr(BedrockProvider.create_message, "__wrapped__", None) is not None


def test_streaming_is_not_wrapped_with_tracing():
    # LSMITH-02: Phase 0 must NOT trace the streaming egress surface.
    assert getattr(AnthropicProvider.create_message_stream, "__wrapped__", None) is None
    assert getattr(BedrockProvider.create_message_stream, "__wrapped__", None) is None


def test_resilient_provider_is_not_wrapped_with_tracing():
    # LSMITH-13: trace at exactly one layer (the concrete providers), never
    # the resilient wrapper — otherwise retries would emit duplicate spans.
    assert getattr(ResilientProvider.create_message, "__wrapped__", None) is None
