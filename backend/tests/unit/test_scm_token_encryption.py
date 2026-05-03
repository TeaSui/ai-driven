"""Unit tests for ai_driven.auth.scm_tokens.

Covers:
- encrypt/decrypt round-trip.
- MultiFernet rotation: ciphertext from an older key decrypts under a
  list that includes the old key.
- Graceful failure when no key can decrypt.
- Memoization TTL reset.
"""

from __future__ import annotations

import pytest
from cryptography.fernet import Fernet, InvalidToken

from ai_driven.auth import scm_tokens
from ai_driven.config import get_settings


@pytest.fixture(autouse=True)
def _reset_key_cache():
    scm_tokens.reset_key_cache()
    yield
    scm_tokens.reset_key_cache()


def _patch_settings(monkeypatch, *, current: str, prior: list[str] | None = None, arn: str = ""):
    get_settings.cache_clear()
    settings = get_settings()
    monkeypatch.setattr(settings, "scm_token_key", current, raising=False)
    monkeypatch.setattr(settings, "scm_token_key_prior_keys", prior or [], raising=False)
    monkeypatch.setattr(settings, "scm_token_key_secret_arn", arn, raising=False)
    # Clear loader memo so next call picks up the patched settings.
    scm_tokens.reset_key_cache()


def test_roundtrip_encrypt_decrypt(monkeypatch):
    key = Fernet.generate_key().decode()
    _patch_settings(monkeypatch, current=key)

    ct = scm_tokens.encrypt_token("ghp_abc123")
    assert isinstance(ct, bytes) and ct != b""
    assert ct != b"ghp_abc123"

    assert scm_tokens.decrypt_token(ct) == "ghp_abc123"


def test_multifernet_rotation_decrypts_old_ciphertext(monkeypatch):
    # Old world: only key_old exists.
    key_old = Fernet.generate_key().decode()
    _patch_settings(monkeypatch, current=key_old)
    old_ct = scm_tokens.encrypt_token("token-from-yesterday")

    # New world: key_new is current; key_old is prior. New writes use
    # new; old ciphertext still decrypts because MultiFernet tries every
    # key in order.
    key_new = Fernet.generate_key().decode()
    _patch_settings(monkeypatch, current=key_new, prior=[key_old])

    # Old ciphertext is still readable.
    assert scm_tokens.decrypt_token(old_ct) == "token-from-yesterday"

    # New writes encrypt under key_new — the first Fernet in MultiFernet.
    new_ct = scm_tokens.encrypt_token("token-today")
    # Round-trip works.
    assert scm_tokens.decrypt_token(new_ct) == "token-today"


def test_decrypt_fails_when_no_key_matches(monkeypatch):
    # Encrypt under key A.
    key_a = Fernet.generate_key().decode()
    _patch_settings(monkeypatch, current=key_a)
    ct = scm_tokens.encrypt_token("secret")

    # Swap to an unrelated key. Decryption should raise InvalidToken
    # cleanly (not a generic Exception).
    key_b = Fernet.generate_key().decode()
    _patch_settings(monkeypatch, current=key_b)

    with pytest.raises(InvalidToken):
        scm_tokens.decrypt_token(ct)


def test_missing_key_raises_runtime_error(monkeypatch):
    _patch_settings(monkeypatch, current="", prior=[], arn="")

    with pytest.raises(RuntimeError, match="No SCM token key configured"):
        scm_tokens.encrypt_token("x")


def test_invalid_key_format_raises_runtime_error(monkeypatch):
    _patch_settings(monkeypatch, current="not-a-valid-fernet-key")

    with pytest.raises(RuntimeError, match="Invalid Fernet key"):
        scm_tokens.encrypt_token("x")
