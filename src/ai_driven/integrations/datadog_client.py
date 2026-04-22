"""DataDog API client for log search, metrics queries, and APM service health."""

from __future__ import annotations

import logging
from typing import Any

import httpx

logger = logging.getLogger(__name__)

_BASE_URL = "https://api.datadoghq.com"

# Timeout for DataDog API requests (seconds)
_REQUEST_TIMEOUT = 30.0

# Maximum allowed limit for log search to prevent excessive data retrieval
_MAX_LOG_LIMIT = 1000


class DataDogClient:
    """Async client for the DataDog API.

    Uses httpx.AsyncClient with DD-API-KEY and DD-APPLICATION-KEY headers
    for authentication.  All methods are async and return parsed JSON.
    """

    def __init__(self, api_key: str = "", app_key: str = "") -> None:
        if not api_key or not app_key:
            logger.warning(
                "DataDog client initialised without credentials — "
                "API calls will fail with 403"
            )
        self._api_key = api_key
        self._app_key = app_key
        self._http = httpx.AsyncClient(
            base_url=_BASE_URL,
            headers={
                "DD-API-KEY": self._api_key,
                "DD-APPLICATION-KEY": self._app_key,
                "Content-Type": "application/json",
            },
            timeout=_REQUEST_TIMEOUT,
        )

    async def close(self) -> None:
        """Close the underlying HTTP client."""
        await self._http.aclose()

    # ------------------------------------------------------------------
    # Log Search API v2
    # ------------------------------------------------------------------

    async def search_logs(
        self,
        query: str,
        from_ts: str,
        to_ts: str,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        """Search logs via the DataDog Log Search API v2.

        Parameters
        ----------
        query:
            DataDog log query string (e.g. ``service:my-app status:error``).
        from_ts:
            Start time in ISO 8601 format.
        to_ts:
            End time in ISO 8601 format.
        limit:
            Maximum number of log entries to return (capped at 1000).
        """
        if not query:
            raise ValueError("query must not be empty")

        # Clamp limit to a safe range
        clamped_limit = max(1, min(limit, _MAX_LOG_LIMIT))

        body: dict[str, Any] = {
            "filter": {
                "query": query,
                "from": from_ts,
                "to": to_ts,
            },
            "page": {
                "limit": clamped_limit,
            },
            "sort": "timestamp",
        }

        resp = await self._http.post("/api/v2/logs/events/search", json=body)
        resp.raise_for_status()
        data = resp.json()

        logs: list[dict[str, Any]] = []
        for event in data.get("data", []):
            attrs = event.get("attributes", {})
            logs.append({
                "id": event.get("id", ""),
                "timestamp": attrs.get("timestamp", ""),
                "status": attrs.get("status", ""),
                "service": attrs.get("service", ""),
                "message": attrs.get("message", ""),
                "host": attrs.get("host", ""),
                "tags": attrs.get("tags", []),
            })

        return logs

    # ------------------------------------------------------------------
    # Metrics Query API v1
    # ------------------------------------------------------------------

    async def query_metrics(
        self,
        query: str,
        from_ts: int,
        to_ts: int,
    ) -> dict[str, Any]:
        """Query metric timeseries via the DataDog Metrics API v1.

        Parameters
        ----------
        query:
            DataDog metrics query (e.g.
            ``avg:system.cpu.user{service:my-app}``).
        from_ts:
            Start time as Unix epoch seconds.
        to_ts:
            End time as Unix epoch seconds.
        """
        if not query:
            raise ValueError("query must not be empty")
        if from_ts >= to_ts:
            raise ValueError("from_ts must be less than to_ts")

        resp = await self._http.get(
            "/api/v1/query",
            params={"query": query, "from": from_ts, "to": to_ts},
        )
        resp.raise_for_status()
        data = resp.json()

        series: list[dict[str, Any]] = []
        for s in data.get("series", []):
            series.append({
                "metric": s.get("metric", ""),
                "display_name": s.get("display_name", ""),
                "unit": s.get("unit", [{}])[0].get("name", "") if s.get("unit") else "",
                "pointlist": s.get("pointlist", []),
                "scope": s.get("scope", ""),
                "tag_set": s.get("tag_set", []),
            })

        return {
            "status": data.get("status", ""),
            "query": data.get("query", query),
            "series": series,
            "from_date": data.get("from_date"),
            "to_date": data.get("to_date"),
        }

    # ------------------------------------------------------------------
    # APM Service Summary
    # ------------------------------------------------------------------

    async def get_service_summary(
        self,
        service_name: str,
    ) -> dict[str, Any]:
        """Retrieve an APM service summary from DataDog.

        Uses the Service Level Objectives and APM stats endpoint to
        provide a high-level health view.

        Parameters
        ----------
        service_name:
            The service name as registered in DataDog APM.
        """
        if not service_name:
            raise ValueError("service_name must not be empty")

        resp = await self._http.get(
            "/api/v1/service_dependencies",
            params={"service": service_name, "env": "production"},
        )
        # The service_dependencies endpoint may not exist in all DD setups.
        # Fall back to a metrics-based summary if it 404s.
        if resp.status_code == 404:
            return await self._build_metrics_summary(service_name)

        resp.raise_for_status()
        return resp.json()

    async def _build_metrics_summary(
        self,
        service_name: str,
    ) -> dict[str, Any]:
        """Build a summary from standard APM metrics when the service
        dependencies endpoint is unavailable."""
        import time

        now = int(time.time())
        one_hour_ago = now - 3600

        error_rate = await self.query_metrics(
            f"sum:trace.http.request.errors{{service:{service_name}}}.as_rate()",
            one_hour_ago,
            now,
        )
        latency = await self.query_metrics(
            f"avg:trace.http.request.duration{{service:{service_name}}}",
            one_hour_ago,
            now,
        )

        return {
            "service": service_name,
            "error_rate": error_rate.get("series", []),
            "latency": latency.get("series", []),
            "period": "last_1h",
        }

    # ------------------------------------------------------------------
    # Active Monitors
    # ------------------------------------------------------------------

    async def get_active_monitors(
        self,
        service_name: str | None = None,
    ) -> list[dict[str, Any]]:
        """List monitors that are currently triggered (Alert/Warn state).

        Parameters
        ----------
        service_name:
            Optional filter to only return monitors for a specific service.
        """
        params: dict[str, Any] = {
            "monitor_tags": f"service:{service_name}" if service_name else "",
        }
        # Only include non-empty params
        params = {k: v for k, v in params.items() if v}

        resp = await self._http.get("/api/v1/monitor", params=params)
        resp.raise_for_status()
        monitors_data = resp.json()

        if not isinstance(monitors_data, list):
            monitors_data = monitors_data.get("monitors", [])

        triggered: list[dict[str, Any]] = []
        for mon in monitors_data:
            state = mon.get("overall_state", "")
            if state in ("Alert", "Warn", "No Data"):
                triggered.append({
                    "id": mon.get("id"),
                    "name": mon.get("name", ""),
                    "state": state,
                    "message": mon.get("message", ""),
                    "type": mon.get("type", ""),
                    "tags": mon.get("tags", []),
                    "query": mon.get("query", ""),
                })

        return triggered
