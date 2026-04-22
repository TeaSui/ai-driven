"""PII masking filter for Python logging.

Masks sensitive data (emails, API keys, phone numbers, JWTs, IP addresses)
in log messages, args, and structured logging extras before they reach handlers.
"""

from __future__ import annotations

import logging
import re
from typing import Any

# All patterns compiled once at module load for performance.

# Email: match local@domain.tld — mask local part to first char + ***
_EMAIL_RE = re.compile(r"([a-zA-Z0-9._%+-])([a-zA-Z0-9._%+-]*)@([a-zA-Z0-9.-]+\.[a-zA-Z]{2,})")

# API keys: aidrv_ prefix followed by alphanumerics
_API_KEY_RE = re.compile(r"(aidrv_[a-zA-Z0-9]{0,4})[a-zA-Z0-9]*")

# Phone: international format with +, digits, dashes, spaces, dots
_PHONE_RE = re.compile(
    r"(\+\d{1,3})"          # country code
    r"([\s.-]?\d{1,4})"     # area / group 1
    r"[\s.-]?\d{1,4}"       # group 2
    r"[\s.-]?\d{1,4}"       # group 3
)

# JWT: base64url segments starting with eyJ (JSON header)
_JWT_RE = re.compile(r"eyJ[A-Za-z0-9_-]{8,}(?:\.[A-Za-z0-9_-]+)*")

# IPv4: four octets — we keep first two octets for subnet, mask last two
_IPV4_RE = re.compile(r"\b(\d{1,3}\.\d{1,3})\.(\d{1,3})\.(\d{1,3})\b")

# Loopback and link-local prefixes that should not be masked
_SAFE_IP_PREFIXES = ("127.0", "0.0")


def _mask_email(match: re.Match) -> str:
    first_char = match.group(1)
    domain = match.group(3)
    return f"{first_char}***@{domain}"


def _mask_api_key(match: re.Match) -> str:
    prefix = match.group(1)
    return f"{prefix}****"


def _mask_phone(match: re.Match) -> str:
    country = match.group(1)
    area = match.group(2)
    return f"{country}{area}-***-****"


def _mask_jwt(match: re.Match) -> str:
    return "eyJ****..."


def _mask_ip(match: re.Match, debug_mode: bool = False) -> str:
    full = match.group(0)
    subnet = match.group(1)

    if debug_mode:
        return full

    # Don't mask loopback
    if any(subnet.startswith(prefix) for prefix in _SAFE_IP_PREFIXES):
        return full

    return f"{subnet}.x.x"


class PIIMaskingFilter(logging.Filter):
    """Logging filter that masks PII in log records.

    Masks:
        - Email addresses: ``u***@example.com``
        - API keys (``aidrv_`` prefix): ``aidrv_abc1****``
        - Phone numbers: ``+1-555-***-****``
        - JWT tokens: ``eyJ****...``
        - IP addresses (non-debug): ``192.168.x.x``

    Args:
        debug_mode: When True, IP addresses are not masked (keeps full IP
            for debugging). All other PII types are always masked.
    """

    # Standard LogRecord attributes that should NOT be scanned as extras
    _STANDARD_ATTRS = frozenset(logging.LogRecord("", 0, "", 0, "", (), None).__dict__)

    def __init__(self, name: str = "", debug_mode: bool = False) -> None:
        super().__init__(name)
        self._debug_mode = debug_mode

    def filter(self, record: logging.LogRecord) -> bool:
        """Mask PII in msg, args, and extra attributes. Always returns True."""
        # Mask the message template
        if isinstance(record.msg, str):
            record.msg = self._mask_text(record.msg)

        # Mask %-style format args
        if record.args is not None:
            record.args = self._mask_args(record.args)

        # Mask extra attributes (structured logging)
        self._mask_extras(record)

        return True

    def _mask_text(self, text: str) -> str:
        """Apply all PII masking patterns to a string."""
        text = _EMAIL_RE.sub(_mask_email, text)
        text = _API_KEY_RE.sub(_mask_api_key, text)
        text = _JWT_RE.sub(_mask_jwt, text)
        text = _PHONE_RE.sub(_mask_phone, text)
        text = _IPV4_RE.sub(lambda m: _mask_ip(m, self._debug_mode), text)
        return text

    def _mask_args(self, args: Any) -> Any:
        """Mask PII in log record args (tuple or dict)."""
        if isinstance(args, dict):
            return {
                key: self._mask_value(val) for key, val in args.items()
            }
        if isinstance(args, (tuple, list)):
            return tuple(self._mask_value(a) for a in args)
        return args

    def _mask_value(self, value: Any) -> Any:
        """Mask a single value if it is a string; recurse into dicts."""
        if isinstance(value, str):
            return self._mask_text(value)
        if isinstance(value, dict):
            return {k: self._mask_value(v) for k, v in value.items()}
        return value

    def _mask_extras(self, record: logging.LogRecord) -> None:
        """Mask PII in non-standard record attributes (extras)."""
        for key, value in record.__dict__.items():
            if key in self._STANDARD_ATTRS:
                continue
            masked = self._mask_value(value)
            if masked is not value:
                setattr(record, key, masked)
