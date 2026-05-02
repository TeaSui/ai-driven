"""Tests for PII masking filter in log output."""

from __future__ import annotations

import logging

import pytest

from ai_driven.observability.pii_filter import PIIMaskingFilter


@pytest.fixture()
def pii_filter() -> PIIMaskingFilter:
    return PIIMaskingFilter()


@pytest.fixture()
def pii_filter_debug() -> PIIMaskingFilter:
    return PIIMaskingFilter(debug_mode=True)


def _make_record(
    msg: str,
    args: tuple | None = None,
    level: int = logging.INFO,
    extra: dict | None = None,
) -> logging.LogRecord:
    record = logging.LogRecord(
        name="test",
        level=level,
        pathname="test.py",
        lineno=1,
        msg=msg,
        args=args,
        exc_info=None,
    )
    if extra:
        for key, value in extra.items():
            setattr(record, key, value)
    return record


# ── Email masking ──────────────────────────────────────────────


class TestEmailMasking:
    def test_simple_email(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("user email is user@example.com ok")
        pii_filter.filter(record)
        assert "user@example.com" not in record.msg
        assert "u***@example.com" in record.msg

    def test_short_local_part(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("email: a@test.com")
        pii_filter.filter(record)
        assert "a@test.com" not in record.msg
        assert "a***@test.com" in record.msg

    def test_long_email(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("contact: longuser@example.org")
        pii_filter.filter(record)
        assert "longuser@example.org" not in record.msg
        assert "l***@example.org" in record.msg

    def test_multiple_emails(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("from alice@a.com to bob@b.com")
        pii_filter.filter(record)
        assert "alice@a.com" not in record.msg
        assert "bob@b.com" not in record.msg
        assert "a***@a.com" in record.msg
        assert "b***@b.com" in record.msg


# ── API key masking ────────────────────────────────────────────


class TestAPIKeyMasking:
    def test_api_key(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("key: aidrv_abc123xyzlong")
        pii_filter.filter(record)
        assert "aidrv_abc123xyzlong" not in record.msg
        assert "aidrv_abc1****" in record.msg

    def test_api_key_short(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("key: aidrv_ab")
        pii_filter.filter(record)
        # The short key should be masked; the result keeps prefix + ****
        assert record.msg == "key: aidrv_ab****"


# ── Phone number masking ──────────────────────────────────────


class TestPhoneMasking:
    def test_us_format(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("call +1-555-123-4567 now")
        pii_filter.filter(record)
        assert "+1-555-123-4567" not in record.msg
        assert "+1-555-***-****" in record.msg

    def test_phone_with_spaces(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("phone: +65 9123 4567")
        pii_filter.filter(record)
        assert "9123 4567" not in record.msg

    def test_phone_compact(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("ph: +15551234567")
        pii_filter.filter(record)
        assert "5551234567" not in record.msg


# ── JWT token masking ─────────────────────────────────────────


class TestJWTMasking:
    def test_jwt_token(self, pii_filter: PIIMaskingFilter) -> None:
        jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        record = _make_record(f"token: {jwt}")
        pii_filter.filter(record)
        assert jwt not in record.msg
        assert "eyJ****..." in record.msg

    def test_short_base64_not_jwt(self, pii_filter: PIIMaskingFilter) -> None:
        """Short base64 strings that happen to start with eyJ should still be masked
        if they look long enough to be a JWT."""
        record = _make_record("val: eyJhbGciOiJIUz")
        pii_filter.filter(record)
        assert "eyJ****..." in record.msg


# ── IP address masking ────────────────────────────────────────


class TestIPMasking:
    def test_ipv4_non_debug(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("from 192.168.1.100")
        pii_filter.filter(record)
        assert "192.168.1.100" not in record.msg
        assert "192.168.x.x" in record.msg

    def test_ipv4_debug_mode(self, pii_filter_debug: PIIMaskingFilter) -> None:
        record = _make_record("from 192.168.1.100")
        pii_filter_debug.filter(record)
        # Debug mode keeps full IP
        assert "192.168.1.100" in record.msg

    def test_loopback_not_masked(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("localhost 127.0.0.1")
        pii_filter.filter(record)
        assert "127.0.0.1" in record.msg

    def test_public_ip(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("origin: 203.45.67.89")
        pii_filter.filter(record)
        assert "203.45.67.89" not in record.msg
        assert "203.45.x.x" in record.msg


# ── Args masking (%-style formatting) ─────────────────────────


class TestArgsMasking:
    def test_string_arg(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("email: %s", ("user@example.com",))
        pii_filter.filter(record)
        assert record.args is not None
        assert "user@example.com" not in record.args[0]
        assert "u***@example.com" in record.args[0]

    def test_non_string_arg_unchanged(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("count: %d", (42,))
        pii_filter.filter(record)
        assert record.args == (42,)

    def test_mixed_args(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record(
            "user %s has %d items from %s",
            ("admin@corp.com", 5, "10.0.0.1"),
        )
        pii_filter.filter(record)
        assert "admin@corp.com" not in record.args[0]
        assert record.args[1] == 5
        assert "10.0.0.1" not in record.args[2]

    def test_dict_args(self, pii_filter: PIIMaskingFilter) -> None:
        """Logger can use %(key)s with dict args."""
        record = _make_record("%(email)s logged in")
        # LogRecord constructor doesn't accept a plain dict for args,
        # so set it manually after creation.
        record.args = {"email": "user@test.com"}
        pii_filter.filter(record)
        assert "user@test.com" not in record.args["email"]


# ── Structured extra masking ──────────────────────────────────


class TestExtraMasking:
    def test_extra_string_value(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record(
            "login event",
            extra={"user_email": "user@example.com"},
        )
        pii_filter.filter(record)
        assert "user@example.com" not in record.user_email
        assert "u***@example.com" in record.user_email

    def test_extra_non_string_unchanged(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("event", extra={"count": 42})
        pii_filter.filter(record)
        assert record.count == 42

    def test_extra_dict_value(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record(
            "event",
            extra={"context": {"email": "user@test.com", "ip": "10.20.30.40"}},
        )
        pii_filter.filter(record)
        assert "user@test.com" not in str(record.context)
        assert "10.20.30.40" not in str(record.context)


# ── Non-PII passthrough ──────────────────────────────────────


class TestNonPIIPassthrough:
    def test_normal_message_unchanged(self, pii_filter: PIIMaskingFilter) -> None:
        msg = "Order 12345 processed successfully"
        record = _make_record(msg)
        pii_filter.filter(record)
        assert record.msg == msg

    def test_empty_message(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("")
        pii_filter.filter(record)
        assert record.msg == ""

    def test_bytes_message_no_crash(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record(b"binary data")  # type: ignore[arg-type]
        pii_filter.filter(record)
        # Should not crash, bytes pass through unchanged
        assert record.msg == b"binary data"


# ── Filter always returns True (record passes through) ────────


class TestFilterReturnValue:
    def test_filter_returns_true(self, pii_filter: PIIMaskingFilter) -> None:
        record = _make_record("test message")
        result = pii_filter.filter(record)
        assert result is True
