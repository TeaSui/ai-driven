"""backend/tests/unit/test_create_provider_impl.py"""
from unittest.mock import patch

from ai_driven.claude.langchain_provider import LangChainProvider
from ai_driven.claude.provider import (
    AnthropicProvider,
    ResilientProvider,
    create_provider,
)
from ai_driven.config import Settings


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
