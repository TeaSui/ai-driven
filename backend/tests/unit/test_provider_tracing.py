"""Tracing wiring on the LangChainProvider (LSMITH-02/13).

Only the unary ``create_message`` is decorated. ``create_message_stream`` and
``ResilientProvider`` are intentionally left undecorated (no streaming egress
in Phase 0; tracing applied at exactly one layer to avoid double spans).
"""

from ai_driven.claude.langchain_provider import LangChainProvider
from ai_driven.claude.provider import ResilientProvider


def test_create_message_is_wrapped_with_tracing():
    assert getattr(LangChainProvider.create_message, "__wrapped__", None) is not None


def test_streaming_is_not_wrapped_with_tracing():
    # LSMITH-02: must NOT trace the streaming surface.
    assert getattr(LangChainProvider.create_message_stream, "__wrapped__", None) is None


def test_resilient_provider_is_not_wrapped_with_tracing():
    # LSMITH-13: trace at exactly one layer (the concrete provider), never
    # the resilient wrapper — otherwise retries would emit duplicate spans.
    assert getattr(ResilientProvider.create_message, "__wrapped__", None) is None
