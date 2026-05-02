"""Tests for API key authentication."""

from ai_driven.auth.middleware import hash_api_key


def test_hash_api_key_deterministic():
    key = "aidrv_test_key_123"
    hash1 = hash_api_key(key)
    hash2 = hash_api_key(key)
    assert hash1 == hash2


def test_hash_api_key_different_keys():
    hash1 = hash_api_key("key_1")
    hash2 = hash_api_key("key_2")
    assert hash1 != hash2
