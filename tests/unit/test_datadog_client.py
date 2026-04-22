"""Unit tests for the DataDogClient."""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from ai_driven.integrations.datadog_client import DataDogClient


@pytest.fixture
def dd_client() -> DataDogClient:
    return DataDogClient(api_key="test-api-key", app_key="test-app-key")


class TestInit:
    """Test DataDogClient initialisation."""

    def test_sets_auth_headers(self) -> None:
        client = DataDogClient(api_key="ak", app_key="appk")
        headers = client._http.headers
        assert headers["DD-API-KEY"] == "ak"
        assert headers["DD-APPLICATION-KEY"] == "appk"

    def test_uses_correct_base_url(self) -> None:
        client = DataDogClient(api_key="ak", app_key="appk")
        assert str(client._http.base_url) == "https://api.datadoghq.com"

    def test_warns_on_empty_credentials(self, caplog: pytest.LogCaptureFixture) -> None:
        import logging
        with caplog.at_level(logging.WARNING):
            DataDogClient(api_key="", app_key="")
        assert "without credentials" in caplog.text


class TestSearchLogs:
    """Test search_logs method."""

    async def test_parses_response(self, dd_client: DataDogClient) -> None:
        mock_response = httpx.Response(
            200,
            json={
                "data": [
                    {
                        "id": "log-1",
                        "attributes": {
                            "timestamp": "2026-04-20T10:00:00Z",
                            "status": "error",
                            "service": "api",
                            "message": "NullPointerException",
                            "host": "host-1",
                            "tags": ["env:prod"],
                        },
                    }
                ]
            },
            request=httpx.Request("POST", "https://api.datadoghq.com/api/v2/logs/events/search"),
        )
        dd_client._http.post = AsyncMock(return_value=mock_response)

        logs = await dd_client.search_logs("service:api status:error", "t1", "t2")
        assert len(logs) == 1
        assert logs[0]["id"] == "log-1"
        assert logs[0]["message"] == "NullPointerException"
        assert logs[0]["service"] == "api"

    async def test_empty_query_raises(self, dd_client: DataDogClient) -> None:
        with pytest.raises(ValueError, match="query must not be empty"):
            await dd_client.search_logs("", "t1", "t2")

    async def test_clamps_limit(self, dd_client: DataDogClient) -> None:
        mock_response = httpx.Response(
            200,
            json={"data": []},
            request=httpx.Request("POST", "https://api.datadoghq.com/api/v2/logs/events/search"),
        )
        dd_client._http.post = AsyncMock(return_value=mock_response)

        await dd_client.search_logs("q", "t1", "t2", limit=5000)
        call_args = dd_client._http.post.call_args
        body = call_args[1]["json"]
        assert body["page"]["limit"] == 1000

    async def test_raises_on_http_error(self, dd_client: DataDogClient) -> None:
        mock_response = httpx.Response(
            403,
            json={"errors": ["Forbidden"]},
            request=httpx.Request("POST", "https://api.datadoghq.com/api/v2/logs/events/search"),
        )
        dd_client._http.post = AsyncMock(return_value=mock_response)

        with pytest.raises(httpx.HTTPStatusError):
            await dd_client.search_logs("q", "t1", "t2")


class TestQueryMetrics:
    """Test query_metrics method."""

    async def test_parses_series(self, dd_client: DataDogClient) -> None:
        mock_response = httpx.Response(
            200,
            json={
                "status": "ok",
                "query": "avg:cpu{*}",
                "series": [
                    {
                        "metric": "system.cpu.user",
                        "display_name": "CPU User",
                        "unit": [{"name": "percent"}],
                        "pointlist": [[1000, 42.5]],
                        "scope": "host:web-1",
                        "tag_set": ["host:web-1"],
                    }
                ],
                "from_date": 1000,
                "to_date": 2000,
            },
            request=httpx.Request("GET", "https://api.datadoghq.com/api/v1/query"),
        )
        dd_client._http.get = AsyncMock(return_value=mock_response)

        result = await dd_client.query_metrics("avg:cpu{*}", 1000, 2000)
        assert result["status"] == "ok"
        assert len(result["series"]) == 1
        assert result["series"][0]["metric"] == "system.cpu.user"
        assert result["series"][0]["unit"] == "percent"

    async def test_empty_query_raises(self, dd_client: DataDogClient) -> None:
        with pytest.raises(ValueError, match="query must not be empty"):
            await dd_client.query_metrics("", 1000, 2000)

    async def test_invalid_time_range_raises(self, dd_client: DataDogClient) -> None:
        with pytest.raises(ValueError, match="from_ts must be less than to_ts"):
            await dd_client.query_metrics("q", 2000, 1000)


class TestGetServiceSummary:
    """Test get_service_summary method."""

    async def test_returns_service_data(self, dd_client: DataDogClient) -> None:
        mock_response = httpx.Response(
            200,
            json={"service": "my-api", "dependencies": []},
            request=httpx.Request("GET", "https://api.datadoghq.com/api/v1/service_dependencies"),
        )
        dd_client._http.get = AsyncMock(return_value=mock_response)

        result = await dd_client.get_service_summary("my-api")
        assert result["service"] == "my-api"

    async def test_empty_service_name_raises(self, dd_client: DataDogClient) -> None:
        with pytest.raises(ValueError, match="service_name must not be empty"):
            await dd_client.get_service_summary("")

    async def test_falls_back_to_metrics_on_404(self, dd_client: DataDogClient) -> None:
        not_found_response = httpx.Response(
            404,
            json={},
            request=httpx.Request("GET", "https://api.datadoghq.com/api/v1/service_dependencies"),
        )
        metrics_response = httpx.Response(
            200,
            json={"status": "ok", "series": []},
            request=httpx.Request("GET", "https://api.datadoghq.com/api/v1/query"),
        )
        # First call returns 404, subsequent calls return metrics data
        dd_client._http.get = AsyncMock(side_effect=[not_found_response, metrics_response, metrics_response])

        result = await dd_client.get_service_summary("my-api")
        assert result["service"] == "my-api"
        assert "error_rate" in result
        assert "latency" in result


class TestGetActiveMonitors:
    """Test get_active_monitors method."""

    async def test_filters_triggered_monitors(self, dd_client: DataDogClient) -> None:
        mock_response = httpx.Response(
            200,
            json=[
                {"id": 1, "name": "High CPU", "overall_state": "Alert", "message": "", "type": "metric", "tags": [], "query": "q1"},
                {"id": 2, "name": "Healthy Check", "overall_state": "OK", "message": "", "type": "metric", "tags": [], "query": "q2"},
                {"id": 3, "name": "Warn Disk", "overall_state": "Warn", "message": "", "type": "metric", "tags": [], "query": "q3"},
            ],
            request=httpx.Request("GET", "https://api.datadoghq.com/api/v1/monitor"),
        )
        dd_client._http.get = AsyncMock(return_value=mock_response)

        monitors = await dd_client.get_active_monitors("my-api")
        assert len(monitors) == 2
        names = {m["name"] for m in monitors}
        assert names == {"High CPU", "Warn Disk"}

    async def test_no_service_filter(self, dd_client: DataDogClient) -> None:
        mock_response = httpx.Response(
            200,
            json=[],
            request=httpx.Request("GET", "https://api.datadoghq.com/api/v1/monitor"),
        )
        dd_client._http.get = AsyncMock(return_value=mock_response)

        monitors = await dd_client.get_active_monitors()
        assert monitors == []


class TestClose:
    """Test client cleanup."""

    async def test_close_calls_aclose(self, dd_client: DataDogClient) -> None:
        dd_client._http.aclose = AsyncMock()
        await dd_client.close()
        dd_client._http.aclose.assert_awaited_once()
