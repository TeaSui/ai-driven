"""Unit tests for ReviewConfig."""

from __future__ import annotations

import pytest

from ai_driven.agents.reviewer.config import ReviewConfig


class TestReviewConfig:
    """Test ReviewConfig construction and serialization."""

    def test_default_config(self) -> None:
        config = ReviewConfig()
        assert config.max_comments >= 1
        assert isinstance(config.ignore_paths, list)
        assert isinstance(config.severity_threshold, str)

    def test_from_settings_empty(self) -> None:
        config = ReviewConfig.from_settings(None)
        default = ReviewConfig()
        assert config.max_comments == default.max_comments
        assert config.ignore_paths == default.ignore_paths

    def test_from_settings_custom(self) -> None:
        config = ReviewConfig.from_settings({
            "review": {
                "max_comments": 5,
                "severity_threshold": "warning",
                "ignore_paths": ["vendor/", "*.lock"],
                "custom_rules": ["No print statements"],
            },
        })
        assert config.max_comments == 5
        assert config.severity_threshold == "warning"
        assert "vendor/" in config.ignore_paths
        assert "No print statements" in config.custom_rules

    def test_to_prompt_section_with_rules(self) -> None:
        config = ReviewConfig(
            max_comments=10,
            custom_rules=["Always check null safety", "Prefer immutable data"],
        )
        section = config.to_prompt_section()
        assert "Always check null safety" in section
        assert "Prefer immutable data" in section

    def test_to_prompt_section_default(self) -> None:
        config = ReviewConfig()
        section = config.to_prompt_section()
        assert "max_comments" in section.lower() or str(config.max_comments) in section

    def test_ignore_paths_is_list(self) -> None:
        config = ReviewConfig()
        assert isinstance(config.ignore_paths, list)
