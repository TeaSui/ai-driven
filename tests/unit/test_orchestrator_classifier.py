"""Unit tests for the tier classifier."""
from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from ai_driven.agents.orchestrator.classifier import (
    TierClassifier,
    parse_tier,
)


def test_parse_tier_strict_match() -> None:
    assert parse_tier("fast_path") == "fast_path"
    assert parse_tier("```\nfull_workflow\n```") == "full_workflow"
    assert parse_tier("multi_domain") == "multi_domain"


def test_parse_tier_unknown_falls_back_to_full_workflow() -> None:
    assert parse_tier("not a tier") == "full_workflow"
    assert parse_tier("") == "full_workflow"


@pytest.mark.asyncio
async def test_classify_calls_haiku_with_temp_zero() -> None:
    classifier = TierClassifier()
    classifier._claude.chat = AsyncMock(return_value=SimpleNamespace(
        text="fast_path", usage=SimpleNamespace(input_tokens=20, output_tokens=2),
    ))
    tier = await classifier.classify(
        ticket={"summary": "Fix typo in README", "description": "tiny"},
        repo={"full_name": "o/r"},
    )
    assert tier == "fast_path"
    classifier._claude.chat.assert_awaited_once()
    call = classifier._claude.chat.await_args
    assert call.kwargs["temperature"] == 0.0
    # Model comes from settings.claude_fast_model. Post-H1 the platform
    # pins every slot to Sonnet 4.6; the indirection (not the literal) is
    # what this test locks — no specialist/classifier should ever
    # hardcode a model string.
    from ai_driven.config import get_settings
    assert call.kwargs["model"] == get_settings().claude_fast_model


@pytest.mark.asyncio
async def test_classify_falls_back_on_infra_error() -> None:
    classifier = TierClassifier()
    classifier._claude.chat = AsyncMock(side_effect=RuntimeError("boom"))
    tier = await classifier.classify(
        ticket={"summary": "x", "description": "y"},
        repo={"full_name": "o/r"},
    )
    assert tier == "full_workflow"
