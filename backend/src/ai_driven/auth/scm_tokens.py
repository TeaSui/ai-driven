"""Fernet-based encryption for per-user SCM OAuth tokens (PR 1).

Two key sources, prod takes precedence:

1. ``scm_token_key_secret_arn`` — AWS Secrets Manager JSON value shaped
   as ``{"current": "<fernet-key>", "prior": ["<old-key>", ...]}``.
   Rotation is driven by updating the secret + running the re-encrypt
   script (documented in docs/runbooks/07-scm-oauth.md).
2. ``scm_token_key`` + ``scm_token_key_prior_keys`` — env-var path for
   dev. Single current key plus zero-or-more accepted-for-decrypt keys.

The returned :class:`MultiFernet` encrypts new writes with the first key
and decrypts with any key in the list (rotation-friendly). The loader
is memoized with a 5-minute TTL so Secrets Manager isn't hit per
request; call :func:`reset_key_cache` from tests.

Key loss is non-recoverable — stored tokens cannot be decrypted. The
SOP (documented in the runbook) is: delete all ``user_scm_connections``
rows and have each user reconnect. The OAuth tokens themselves remain
valid at the provider until the user revokes on
``github.com/settings/applications``.
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any

from cryptography.fernet import Fernet, MultiFernet

from ai_driven.config import get_settings

logger = logging.getLogger(__name__)

# Memoization — (multi_fernet, expires_at_monotonic). Simple module-level
# cache is fine because settings don't change mid-process and we want the
# 5-min TTL to bound the blast radius of a key rotation.
_CACHE_TTL_SECONDS = 300.0
_cache: tuple[MultiFernet, float] | None = None


def reset_key_cache() -> None:
    """Clear the memoized key — used by tests and rotation scripts."""
    global _cache
    _cache = None


def _load_from_aws_secrets_manager(arn: str) -> list[str]:
    """Return ``[current, *prior]`` keys pulled from Secrets Manager.

    Expected secret payload:

        {"current": "<fernet-key>", "prior": ["<old-key>", ...]}

    ``prior`` is optional. Raises ``RuntimeError`` on any parse / boto3
    failure so the caller surfaces a clean 5xx rather than leaking the
    underlying AWS error.
    """
    try:
        import boto3  # local import — boto3 is heavy, only prod path needs it
    except ImportError as ex:  # pragma: no cover — boto3 is a hard dep
        raise RuntimeError("boto3 required for SCM token key from Secrets Manager") from ex

    client = boto3.client("secretsmanager")
    try:
        resp = client.get_secret_value(SecretId=arn)
    except Exception as ex:
        raise RuntimeError(f"Failed to load SCM token key from Secrets Manager: {type(ex).__name__}") from ex

    raw = resp.get("SecretString") or ""
    try:
        payload: dict[str, Any] = json.loads(raw)
    except json.JSONDecodeError as ex:
        raise RuntimeError("SCM token key secret is not valid JSON") from ex

    current = payload.get("current")
    if not current or not isinstance(current, str):
        raise RuntimeError("SCM token key secret missing 'current' field")
    prior = payload.get("prior") or []
    if not isinstance(prior, list):
        raise RuntimeError("SCM token key secret 'prior' must be a list")
    return [current, *[k for k in prior if isinstance(k, str) and k]]


def get_scm_token_key() -> MultiFernet:
    """Return a :class:`MultiFernet` configured with current + prior keys.

    First key encrypts new writes; all keys can decrypt (rotation).
    Raises :class:`RuntimeError` when no key is configured — the error
    propagates to the endpoint handler which returns a 5xx envelope.
    """
    global _cache
    now = time.monotonic()
    if _cache is not None and now < _cache[1]:
        return _cache[0]

    settings = get_settings()
    if settings.scm_token_key_secret_arn:
        keys = _load_from_aws_secrets_manager(settings.scm_token_key_secret_arn)
    elif settings.scm_token_key:
        keys = [settings.scm_token_key, *settings.scm_token_key_prior_keys]
    else:
        raise RuntimeError(
            "No SCM token key configured — set AI_DRIVEN_SCM_TOKEN_KEY (dev) "
            "or AI_DRIVEN_SCM_TOKEN_KEY_SECRET_ARN (prod)."
        )

    fernets = []
    for k in keys:
        if not k:
            continue
        try:
            fernets.append(Fernet(k.encode() if isinstance(k, str) else k))
        except Exception as ex:
            raise RuntimeError(f"Invalid Fernet key configured: {type(ex).__name__}") from ex

    if not fernets:
        raise RuntimeError("No valid SCM token keys parsed from configuration")

    mf = MultiFernet(fernets)
    _cache = (mf, now + _CACHE_TTL_SECONDS)
    return mf


def encrypt_token(plaintext: str) -> bytes:
    """Encrypt a provider OAuth token for storage in ``*_enc`` BYTEA columns."""
    if not isinstance(plaintext, str):
        raise TypeError("encrypt_token expects a str plaintext")
    mf = get_scm_token_key()
    return mf.encrypt(plaintext.encode("utf-8"))


def decrypt_token(ciphertext: bytes) -> str:
    """Decrypt a ciphertext column back to the plaintext OAuth token.

    Raises :class:`cryptography.fernet.InvalidToken` when no configured
    key can decrypt the value — callers should convert this into a
    502/412 and log without the plaintext.
    """
    if not isinstance(ciphertext, (bytes, bytearray, memoryview)):
        raise TypeError("decrypt_token expects bytes")
    mf = get_scm_token_key()
    return mf.decrypt(bytes(ciphertext)).decode("utf-8")
