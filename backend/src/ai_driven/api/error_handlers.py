"""Centralized error response handlers for FastAPI.

Catches all unhandled exceptions and returns sanitized JSON responses
following the standard error envelope: {"error": {"code", "message", "details"}}.
Full tracebacks are logged server-side but never returned to clients.
"""

from __future__ import annotations

import logging
import traceback

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger(__name__)

_MAX_DEBUG_MESSAGE_LENGTH = 200

# Patterns that must never appear in client-facing messages, even in debug mode.
_SENSITIVE_PATTERNS = (
    "SELECT ",
    "INSERT ",
    "UPDATE ",
    "DELETE ",
    "FROM ",
    "WHERE ",
    "JOIN ",
    "/home/",
    "/Users/",
    "/app/",
    "/var/",
    "/etc/",
    "password",
    "secret",
    "token",
    "api_key",
    "apikey",
    "credential",
)


# ── Response Models (for OpenAPI docs) ──────────────────────────────────


class ErrorDetail(BaseModel):
    """Single error detail following the standard error envelope."""

    code: str
    message: str
    details: list[str] = []


class ErrorResponse(BaseModel):
    """Top-level error envelope returned to clients."""

    error: ErrorDetail


# ── Helpers ─────────────────────────────────────────────────────────────


def _contains_sensitive_content(text: str) -> bool:
    """Return True if *text* contains patterns that should never leak."""
    upper = text.upper()
    for pattern in _SENSITIVE_PATTERNS:
        if pattern.upper() in upper:
            return True
    return False


def _sanitize_debug_message(exc: BaseException) -> str | None:
    """Build a truncated, sanitized message for debug-mode responses.

    Returns ``None`` when the raw message contains sensitive content so
    the caller can fall back to the generic message.
    """
    raw = str(exc)
    if not raw or _contains_sensitive_content(raw):
        return None
    if len(raw) > _MAX_DEBUG_MESSAGE_LENGTH:
        return raw[:_MAX_DEBUG_MESSAGE_LENGTH] + "..."
    return raw


def _is_debug_mode() -> bool:
    """True when the app is running outside production — controls whether
    stack traces appear in error responses."""
    try:
        from ai_driven.config import get_settings

        return get_settings().environment.value != "production"
    except Exception:
        return False


def _build_error_response(
    status_code: int,
    code: str,
    message: str,
    details: list[str] | None = None,
) -> JSONResponse:
    body = ErrorResponse(
        error=ErrorDetail(
            code=code,
            message=message,
            details=details or [],
        )
    )
    return JSONResponse(
        status_code=status_code,
        content=body.model_dump(),
    )


# ── Exception Handlers ─────────────────────────────────────────────────


async def generic_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Catch-all handler for unexpected exceptions.

    Logs the full traceback at ERROR level for ops, but returns only a
    sanitised JSON envelope to the client.
    """
    try:
        logger.error(
            "Unhandled exception on %s %s: %s",
            request.method,
            request.url.path,
            exc,
            exc_info=True,
        )

        message = "An unexpected error occurred"

        if _is_debug_mode():
            debug_msg = _sanitize_debug_message(exc)
            if debug_msg is not None:
                message = debug_msg

        return _build_error_response(
            status_code=500,
            code="INTERNAL_ERROR",
            message=message,
        )
    except Exception:
        # Safety net: if the handler itself fails, still return valid JSON.
        logger.critical(
            "Error handler itself raised an exception",
            exc_info=True,
        )
        return JSONResponse(
            status_code=500,
            content={
                "error": {
                    "code": "INTERNAL_ERROR",
                    "message": "An unexpected error occurred",
                    "details": [],
                }
            },
        )


async def http_exception_handler(
    request: Request, exc: StarletteHTTPException
) -> JSONResponse:
    """Wrap FastAPI/Starlette ``HTTPException`` in the standard error envelope.

    Preserves the original status code.
    """
    try:
        detail_str = str(exc.detail) if exc.detail else "An error occurred"

        return _build_error_response(
            status_code=exc.status_code,
            code=f"HTTP_{exc.status_code}",
            message=detail_str,
        )
    except Exception:
        logger.critical(
            "HTTP error handler itself raised an exception",
            exc_info=True,
        )
        return JSONResponse(
            status_code=500,
            content={
                "error": {
                    "code": "INTERNAL_ERROR",
                    "message": "An unexpected error occurred",
                    "details": [],
                }
            },
        )


async def validation_exception_handler(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    """Handle Pydantic / FastAPI request-validation errors (422).

    Returns field-level details without exposing internal model names.
    """
    try:
        details: list[str] = []
        for err in exc.errors():
            loc = " -> ".join(str(part) for part in err.get("loc", []))
            msg = err.get("msg", "invalid")
            details.append(f"{loc}: {msg}")

        return _build_error_response(
            status_code=422,
            code="VALIDATION_ERROR",
            message="Invalid request",
            details=details,
        )
    except Exception:
        logger.critical(
            "Validation error handler itself raised an exception",
            exc_info=True,
        )
        return JSONResponse(
            status_code=422,
            content={
                "error": {
                    "code": "VALIDATION_ERROR",
                    "message": "Invalid request",
                    "details": [],
                }
            },
        )


def register_error_handlers(app: "FastAPI") -> None:  # noqa: F821
    """Register all error handlers on the given FastAPI application."""
    from fastapi import FastAPI  # deferred to avoid circular imports at module level

    app.add_exception_handler(Exception, generic_exception_handler)  # type: ignore[arg-type]
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)  # type: ignore[arg-type]
    app.add_exception_handler(RequestValidationError, validation_exception_handler)  # type: ignore[arg-type]
