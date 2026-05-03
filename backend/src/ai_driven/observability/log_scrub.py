"""Logging filters for scrubbing sensitive values from access logs.

OAuth callbacks arrive as ``GET /api/auth/scm/github/callback?code=<...>
&state=<...>`` — uvicorn.access logs the full query string. A
middleware cannot scrub access logs because uvicorn's
``AccessLoggerMiddleware`` formats the log line AFTER our middleware
stack runs. The correct place to intervene is a :class:`logging.Filter`
attached directly to the ``uvicorn.access`` logger.

Also useful anywhere else the string ``code=<value>`` could appear in
a log record's message (e.g. error tracebacks from httpx).
"""

from __future__ import annotations

import logging
import re

# Matches ``code=<value>`` where <value> is any sequence of URL-safe
# base64 / hex chars. GitHub codes are 20 char alphanumerics; Bitbucket
# codes are longer base64-url. Be generous to avoid missing variants.
_CODE_PATTERN = re.compile(r"code=[A-Za-z0-9_.\-+/=]+")


class ScrubOAuthCodeFilter(logging.Filter):
    """Redact ``code=<value>`` from log record messages + args.

    Rewrites ``record.msg`` in-place when it's a string containing
    ``code=``. Also scrubs any ``str`` positional args so format-string
    logs (``logger.info("got %s", url)``) don't leak the code after
    the formatter joins them.

    Idempotent — running twice produces the same result.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str) and "code=" in record.msg:
            record.msg = _CODE_PATTERN.sub("code=<redacted>", record.msg)
        if record.args:
            new_args: list = []
            changed = False
            iterable_args = record.args if isinstance(record.args, tuple) else (record.args,)
            for a in iterable_args:
                if isinstance(a, str) and "code=" in a:
                    new_args.append(_CODE_PATTERN.sub("code=<redacted>", a))
                    changed = True
                else:
                    new_args.append(a)
            if changed:
                record.args = tuple(new_args)
        # Also handle pre-formatted access log lines that live on
        # record.getMessage() via record.__dict__ (uvicorn formats
        # via msg % args — our msg rewrite above covers it).
        return True
