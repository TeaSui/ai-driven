"""Tests for LangSmith settings (Phase 0 of docs/design/langgraph-migration.md)."""

from ai_driven.config import Settings


def test_langsmith_disabled_by_default():
    s = Settings(_env_file=None)
    assert s.langsmith_enabled is False
    assert s.langsmith_project == "ai-driven-dev"
    assert s.langsmith_mask_payloads is True


def test_langsmith_has_no_api_key_setting():
    # The API key must come ONLY from the LANGSMITH_API_KEY env var read by
    # the langsmith SDK itself — never a pydantic setting that could end up
    # in dumps/logs (LSMITH-05).
    assert not hasattr(Settings(_env_file=None), "langsmith_api_key")
