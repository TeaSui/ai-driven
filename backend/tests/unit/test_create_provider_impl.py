"""Factory wiring: create_provider always returns LangChainProvider."""
from unittest.mock import patch

from ai_driven.claude.langchain_provider import LangChainProvider
from ai_driven.claude.provider import (
    ResilientProvider,
    create_provider,
)
from ai_driven.config import Settings


def _settings(**over):
    return Settings(_env_file=None, anthropic_api_key="test-key", **over)


def test_create_provider_returns_langchain():
    with patch("ai_driven.claude.provider.get_settings", return_value=_settings()):
        p = create_provider(resilient=False)
    assert isinstance(p, LangChainProvider)


def test_create_provider_wrapped_resilient():
    with patch("ai_driven.claude.provider.get_settings", return_value=_settings()):
        p = create_provider(resilient=True)
    assert isinstance(p, ResilientProvider)
