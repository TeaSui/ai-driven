"""Tests for centralized error response handlers."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.exceptions import RequestValidationError
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

from ai_driven.api.error_handlers import (
    ErrorDetail,
    ErrorResponse,
    _contains_sensitive_content,
    _sanitize_debug_message,
    generic_exception_handler,
    http_exception_handler,
    register_error_handlers,
    validation_exception_handler,
)


# ── Test Models ─────────────────────────────────────────────────────────


class ItemRequest(BaseModel):
    name: str = Field(..., min_length=1)
    quantity: int = Field(..., gt=0)


# ── Helpers ─────────────────────────────────────────────────────────────


def _create_test_app(*, debug: bool = False) -> FastAPI:
    """Build a minimal FastAPI app with error handlers registered."""
    app = FastAPI()
    register_error_handlers(app)

    @app.get("/raise-unhandled")
    async def raise_unhandled() -> None:
        raise RuntimeError("Something broke internally")

    @app.get("/raise-http-404")
    async def raise_http_404() -> None:
        raise HTTPException(status_code=404, detail="Order not found")

    @app.get("/raise-http-403")
    async def raise_http_403() -> None:
        raise HTTPException(status_code=403, detail="Forbidden")

    @app.post("/validate")
    async def validate_body(body: ItemRequest) -> dict:
        return {"data": body.model_dump()}

    @app.get("/raise-sql-error")
    async def raise_sql_error() -> None:
        raise RuntimeError("SELECT * FROM users WHERE id = 1; connection reset")

    @app.get("/raise-path-error")
    async def raise_path_error() -> None:
        raise RuntimeError("FileNotFoundError: /Users/dev/app/secret.conf not found")

    @app.get("/raise-handler-error")
    async def raise_handler_error() -> None:
        # This will be caught by the generic handler
        raise ValueError("simple value error")

    return app


def _mock_settings(*, local: bool = False) -> MagicMock:
    """Mock settings for error-handler tests.

    The handler gates stack-trace exposure on ``settings.environment.value
    != "production"``. ``local=True`` mimics local-dev behaviour (stack
    traces exposed); ``local=False`` mimics production (sanitized).
    """
    settings = MagicMock()
    settings.environment = MagicMock()
    settings.environment.value = "local" if local else "production"
    return settings


# ── Unit Tests: Sensitive Content Detection ─────────────────────────────


class TestSensitiveContentDetection:
    def test_detects_sql_select(self) -> None:
        assert _contains_sensitive_content("SELECT * FROM users") is True

    def test_detects_sql_insert(self) -> None:
        assert _contains_sensitive_content("INSERT INTO orders VALUES") is True

    def test_detects_file_paths(self) -> None:
        assert _contains_sensitive_content("/Users/dev/app/main.py") is True
        assert _contains_sensitive_content("/home/ubuntu/app") is True
        assert _contains_sensitive_content("/app/src/main.py") is True

    def test_detects_secrets(self) -> None:
        assert _contains_sensitive_content("password=hunter2") is True
        assert _contains_sensitive_content("api_key is missing") is True
        assert _contains_sensitive_content("invalid token") is True

    def test_allows_safe_content(self) -> None:
        assert _contains_sensitive_content("Division by zero") is False
        assert _contains_sensitive_content("Connection refused") is False

    def test_case_insensitive(self) -> None:
        assert _contains_sensitive_content("select * from users") is True
        assert _contains_sensitive_content("PASSWORD mismatch") is True


class TestSanitizeDebugMessage:
    def test_returns_short_message(self) -> None:
        exc = RuntimeError("Division by zero")
        assert _sanitize_debug_message(exc) == "Division by zero"

    def test_truncates_long_message(self) -> None:
        exc = RuntimeError("x" * 300)
        result = _sanitize_debug_message(exc)
        assert result is not None
        assert len(result) == 203  # 200 + "..."
        assert result.endswith("...")

    def test_returns_none_for_sensitive(self) -> None:
        exc = RuntimeError("SELECT * FROM users")
        assert _sanitize_debug_message(exc) is None

    def test_returns_none_for_empty(self) -> None:
        exc = RuntimeError("")
        assert _sanitize_debug_message(exc) is None


# ── Integration Tests: HTTP Responses ───────────────────────────────────


class TestGenericExceptionHandler:
    """Tests for the catch-all Exception handler.

    Uses ``raise_app_exceptions=False`` on ASGITransport so that httpx
    returns the 500 response instead of re-raising the server exception.
    """

    @pytest.mark.asyncio
    async def test_unhandled_exception_returns_500(self) -> None:
        app = _create_test_app()
        transport = ASGITransport(app=app, raise_app_exceptions=False)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            with patch(
                "ai_driven.config.get_settings",
                return_value=_mock_settings(local=False),
            ):
                resp = await client.get("/raise-unhandled")

        assert resp.status_code == 500
        body = resp.json()
        assert body["error"]["code"] == "INTERNAL_ERROR"
        assert body["error"]["message"] == "An unexpected error occurred"
        assert body["error"]["details"] == []
        # Must NOT contain the actual exception text
        assert "Something broke" not in body["error"]["message"]

    @pytest.mark.asyncio
    async def test_unhandled_exception_debug_mode_shows_truncated_message(self) -> None:
        app = _create_test_app()
        transport = ASGITransport(app=app, raise_app_exceptions=False)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            with patch(
                "ai_driven.config.get_settings",
                return_value=_mock_settings(local=True),
            ):
                resp = await client.get("/raise-unhandled")

        assert resp.status_code == 500
        body = resp.json()
        assert body["error"]["code"] == "INTERNAL_ERROR"
        assert "Something broke internally" in body["error"]["message"]

    @pytest.mark.asyncio
    async def test_debug_mode_hides_sql_in_error(self) -> None:
        app = _create_test_app()
        transport = ASGITransport(app=app, raise_app_exceptions=False)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            with patch(
                "ai_driven.config.get_settings",
                return_value=_mock_settings(local=True),
            ):
                resp = await client.get("/raise-sql-error")

        assert resp.status_code == 500
        body = resp.json()
        # Even in local env where stack traces are exposed, SQL should be hidden
        assert "SELECT" not in body["error"]["message"]
        assert body["error"]["message"] == "An unexpected error occurred"

    @pytest.mark.asyncio
    async def test_debug_mode_hides_file_paths(self) -> None:
        app = _create_test_app()
        transport = ASGITransport(app=app, raise_app_exceptions=False)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            with patch(
                "ai_driven.config.get_settings",
                return_value=_mock_settings(local=True),
            ):
                resp = await client.get("/raise-path-error")

        assert resp.status_code == 500
        body = resp.json()
        assert "/Users/" not in body["error"]["message"]
        assert body["error"]["message"] == "An unexpected error occurred"

    @pytest.mark.asyncio
    async def test_response_never_contains_traceback(self) -> None:
        app = _create_test_app()
        transport = ASGITransport(app=app, raise_app_exceptions=False)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            with patch(
                "ai_driven.config.get_settings",
                return_value=_mock_settings(local=True),
            ):
                resp = await client.get("/raise-unhandled")

        body_text = resp.text
        assert "Traceback" not in body_text
        assert "File " not in body_text


class TestHTTPExceptionHandler:
    @pytest.mark.asyncio
    async def test_404_preserves_status_code(self) -> None:
        app = _create_test_app()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            resp = await client.get("/raise-http-404")

        assert resp.status_code == 404
        body = resp.json()
        assert body["error"]["code"] == "HTTP_404"
        assert body["error"]["message"] == "Order not found"

    @pytest.mark.asyncio
    async def test_403_preserves_status_code(self) -> None:
        app = _create_test_app()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            resp = await client.get("/raise-http-403")

        assert resp.status_code == 403
        body = resp.json()
        assert body["error"]["code"] == "HTTP_403"
        assert body["error"]["message"] == "Forbidden"

    @pytest.mark.asyncio
    async def test_error_envelope_structure(self) -> None:
        app = _create_test_app()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            resp = await client.get("/raise-http-404")

        body = resp.json()
        assert "error" in body
        assert "code" in body["error"]
        assert "message" in body["error"]
        assert "details" in body["error"]
        assert isinstance(body["error"]["details"], list)


class TestValidationExceptionHandler:
    @pytest.mark.asyncio
    async def test_missing_required_field(self) -> None:
        app = _create_test_app()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            resp = await client.post("/validate", json={})

        assert resp.status_code == 422
        body = resp.json()
        assert body["error"]["code"] == "VALIDATION_ERROR"
        assert body["error"]["message"] == "Invalid request"
        assert len(body["error"]["details"]) > 0

    @pytest.mark.asyncio
    async def test_invalid_field_value(self) -> None:
        app = _create_test_app()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            resp = await client.post(
                "/validate", json={"name": "widget", "quantity": -5}
            )

        assert resp.status_code == 422
        body = resp.json()
        assert body["error"]["code"] == "VALIDATION_ERROR"
        # Should mention the field in details
        details_joined = " ".join(body["error"]["details"])
        assert "quantity" in details_joined.lower()

    @pytest.mark.asyncio
    async def test_valid_request_passes(self) -> None:
        app = _create_test_app()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            resp = await client.post(
                "/validate", json={"name": "widget", "quantity": 3}
            )

        assert resp.status_code == 200
        body = resp.json()
        assert body["data"]["name"] == "widget"


class TestErrorResponseModel:
    def test_error_response_serialization(self) -> None:
        resp = ErrorResponse(
            error=ErrorDetail(
                code="INTERNAL_ERROR",
                message="An unexpected error occurred",
                details=[],
            )
        )
        data = resp.model_dump()
        assert data == {
            "error": {
                "code": "INTERNAL_ERROR",
                "message": "An unexpected error occurred",
                "details": [],
            }
        }

    def test_error_detail_defaults(self) -> None:
        detail = ErrorDetail(code="TEST", message="test message")
        assert detail.details == []
