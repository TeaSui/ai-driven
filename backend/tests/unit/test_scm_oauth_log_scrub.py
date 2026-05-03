"""Unit test for the log-scrubbing filter.

Covers the core redaction behavior — we verify the filter mutates the
``record.msg`` in-place so when the formatter eventually joins msg+args
the ``code=`` value is gone.
"""

from __future__ import annotations

import logging

from ai_driven.observability.log_scrub import ScrubOAuthCodeFilter


def _make_record(msg: str, args: tuple = ()) -> logging.LogRecord:
    return logging.LogRecord(
        name="uvicorn.access",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg=msg,
        args=args,
        exc_info=None,
    )


def test_scrubs_code_param_in_msg():
    f = ScrubOAuthCodeFilter()
    rec = _make_record(
        '127.0.0.1:0 - "GET /api/auth/scm/github/callback?code=abc123&state=xyz HTTP/1.1" 302'
    )
    f.filter(rec)
    assert "code=abc123" not in rec.msg
    assert "code=<redacted>" in rec.msg
    # Non-code query params survive.
    assert "state=xyz" in rec.msg


def test_scrubs_code_in_args():
    f = ScrubOAuthCodeFilter()
    rec = _make_record("%s", args=("code=abc123&state=xyz",))
    f.filter(rec)
    assert rec.args == ("code=<redacted>&state=xyz",)


def test_no_change_when_no_code():
    f = ScrubOAuthCodeFilter()
    rec = _make_record("GET /health 200")
    f.filter(rec)
    assert rec.msg == "GET /health 200"


def test_idempotent():
    f = ScrubOAuthCodeFilter()
    rec = _make_record("code=abc123")
    f.filter(rec)
    once = rec.msg
    f.filter(rec)
    assert rec.msg == once


def test_access_log_shape_is_scrubbed():
    """Shape-check against uvicorn.access's actual log format."""
    f = ScrubOAuthCodeFilter()
    # uvicorn.access uses: "%(client_addr)s - \"%(request_line)s\" %(status_code)s"
    # Real line after interpolation would look like:
    raw = '127.0.0.1:51234 - "GET /api/auth/scm/github/callback?code=dead.beef_01-xyz&state=abc HTTP/1.1" 302 Found'
    rec = _make_record(raw)
    f.filter(rec)
    assert "dead.beef_01-xyz" not in rec.msg
    assert "code=<redacted>" in rec.msg
