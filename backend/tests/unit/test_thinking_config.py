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


@pytest.mark.parametrize(
    "bedrock_id,expected",
    [
        ("global.anthropic.claude-opus-4-7",                {"type": "adaptive"}),
        ("global.anthropic.claude-opus-4-6-v1",             {"type": "enabled", "budget_tokens": 16000}),
        ("global.anthropic.claude-sonnet-4-6",              {"type": "enabled", "budget_tokens": 16000}),
        ("global.anthropic.claude-haiku-4-5-20251001-v1:0", None),
    ],
)
def test_real_bedrock_model_ids(bedrock_id: str, expected) -> None:
    """Covers the actual Bedrock IDs produced by provider.py's mapping table."""
    from ai_driven.claude.thinking import resolve_thinking_config

    assert resolve_thinking_config(bedrock_id, budget_tokens=16000) == expected
