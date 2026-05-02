"""Tests for application configuration."""

import os
from unittest.mock import patch

import pytest


def test_default_settings():
    """Settings should load with defaults when no env vars are set."""
    from ai_driven.config import Settings

    settings = Settings(_env_file=None)
    assert settings.app_name == "ai-driven"
    assert settings.api_port == 8000
    assert settings.claude_default_model == "claude-sonnet-4-6"
    assert settings.aws_region == "eu-central-1"


def test_postgres_url():
    from ai_driven.config import Settings

    settings = Settings(
        postgres_host="db.example.com",
        postgres_port=5432,
        postgres_user="testuser",
        postgres_password="testpass",
        postgres_db="testdb",
    )
    assert settings.postgres_url == "postgresql+asyncpg://testuser:testpass@db.example.com:5432/testdb"


def test_claude_provider_types():
    from ai_driven.config import ClaudeProviderType

    assert ClaudeProviderType.ANTHROPIC == "anthropic"
    assert ClaudeProviderType.BEDROCK == "bedrock"
