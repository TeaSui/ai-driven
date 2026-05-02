"""Unit tests for new orchestrator config knobs."""
from __future__ import annotations

import os
from unittest.mock import patch

from ai_driven.config import Settings, get_settings


def test_orchestrator_mode_defaults_to_shadow() -> None:
    s = Settings()
    assert s.orchestrator_mode == "shadow"


def test_orchestrator_mode_accepts_shadow_live_off() -> None:
    for v in ("shadow", "live", "off"):
        s = Settings(orchestrator_mode=v)
        assert s.orchestrator_mode == v


def test_new_sqs_queue_urls_default_empty() -> None:
    s = Settings()
    assert s.sqs_orchestrator_queue_url == ""
    assert s.sqs_qa_queue_url == ""


def test_orchestrator_mode_env_var_picks_up_prefix() -> None:
    with patch.dict(os.environ, {"AI_DRIVEN_ORCHESTRATOR_MODE": "live"}):
        get_settings.cache_clear()
        try:
            assert get_settings().orchestrator_mode == "live"
        finally:
            get_settings.cache_clear()
