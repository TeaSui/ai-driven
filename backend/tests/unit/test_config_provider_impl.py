"""backend/tests/unit/test_config_provider_impl.py"""
import pytest
from pydantic import ValidationError

from ai_driven.config import Settings


def test_provider_impl_defaults_to_legacy():
    assert Settings(_env_file=None).claude_provider_impl == "legacy"


def test_provider_impl_accepts_langchain():
    assert Settings(_env_file=None, claude_provider_impl="langchain").claude_provider_impl == "langchain"


def test_provider_impl_rejects_unknown():
    with pytest.raises(ValidationError):
        Settings(_env_file=None, claude_provider_impl="autogen")
