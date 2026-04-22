"""Unit tests for ai_driven.observability.alerts."""

from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

import pytest

from ai_driven.observability.alerts import AlertDefinition, AlertRegistry, AlertThresholds


# ---------------------------------------------------------------------------
# AlertThresholds
# ---------------------------------------------------------------------------

class TestAlertThresholds:
    def test_defaults(self) -> None:
        t = AlertThresholds()
        assert t.error_rate_critical == pytest.approx(0.01)
        assert t.error_rate_warning == pytest.approx(0.005)
        assert t.latency_critical_ms == 10_000
        assert t.latency_warning_ms == 5_000
        assert t.spend_critical_fraction == pytest.approx(0.8)
        assert t.spend_warning_fraction == pytest.approx(0.6)
        assert t.queue_depth_critical == 100
        assert t.queue_depth_warning == 50
        assert t.daily_budget_usd == pytest.approx(50.0)

    def test_spend_critical_usd_derived(self) -> None:
        t = AlertThresholds()
        assert t.spend_critical_usd == pytest.approx(40.0)

    def test_spend_warning_usd_derived(self) -> None:
        t = AlertThresholds()
        assert t.spend_warning_usd == pytest.approx(30.0)

    def test_env_var_override(self, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.setenv("ALERT_ERROR_RATE_CRITICAL", "0.05")
        monkeypatch.setenv("AI_DRIVEN_DAILY_BUDGET_USD", "100.0")
        t = AlertThresholds()
        assert t.error_rate_critical == pytest.approx(0.05)
        assert t.daily_budget_usd == pytest.approx(100.0)


# ---------------------------------------------------------------------------
# AlertDefinition
# ---------------------------------------------------------------------------

class TestAlertDefinition:
    def test_to_datadog_payload_shape(self) -> None:
        alert = AlertDefinition(
            name="Test alert",
            query="avg(last_5m):sum:foo{*} > 1",
            message="Firing",
            priority=1,
            thresholds={"critical": 1.0, "warning": 0.5},
            tags=["env:test"],
        )
        payload = alert.to_datadog_payload()

        assert payload["name"] == "Test alert"
        assert payload["type"] == "query alert"
        assert payload["query"] == "avg(last_5m):sum:foo{*} > 1"
        assert payload["message"] == "Firing"
        assert payload["priority"] == 1
        assert payload["tags"] == ["env:test"]
        assert payload["options"]["thresholds"] == {"critical": 1.0, "warning": 0.5}
        assert payload["options"]["notify_no_data"] is False

    def test_to_datadog_payload_is_json_serialisable(self) -> None:
        alert = AlertDefinition(
            name="Serialisable",
            query="avg(last_5m):sum:foo > 1",
            message="msg",
            priority=2,
            thresholds={"critical": 1.0},
        )
        # Should not raise
        json.dumps(alert.to_datadog_payload())


# ---------------------------------------------------------------------------
# AlertRegistry — monitor count and structure
# ---------------------------------------------------------------------------

class TestAlertRegistryBuild:
    def setup_method(self) -> None:
        self.registry = AlertRegistry(env="test")
        self.alerts = self.registry.build()

    def test_builds_expected_number_of_alerts(self) -> None:
        # 1 error-rate + 1 latency + 1 spend + 1 queue-depth + 3 DLQ = 7
        assert len(self.alerts) == 7

    def test_all_alerts_have_names(self) -> None:
        for alert in self.alerts:
            assert alert.name, f"Alert has empty name: {alert}"

    def test_all_alerts_have_queries(self) -> None:
        for alert in self.alerts:
            assert alert.query, f"Alert has empty query: {alert.name}"

    def test_all_alerts_have_messages(self) -> None:
        for alert in self.alerts:
            assert alert.message, f"Alert has empty message: {alert.name}"

    def test_p1_alerts_are_priority_1(self) -> None:
        p1_alerts = [a for a in self.alerts if "P1" in a.name]
        for alert in p1_alerts:
            assert alert.priority == 1, f"{alert.name} should be priority 1"

    def test_p2_alerts_are_priority_2(self) -> None:
        p2_alerts = [a for a in self.alerts if "P2" in a.name]
        for alert in p2_alerts:
            assert alert.priority == 2, f"{alert.name} should be priority 2"

    def test_dlq_alerts_are_p1(self) -> None:
        dlq_alerts = [a for a in self.alerts if "DLQ" in a.name]
        assert len(dlq_alerts) == 3
        for alert in dlq_alerts:
            assert alert.priority == 1
            assert alert.thresholds["critical"] == 0.0

    def test_error_rate_alert_contains_env_scope(self) -> None:
        error_alerts = [a for a in self.alerts if "error rate" in a.name.lower()]
        assert len(error_alerts) == 1
        assert "env:test" in error_alerts[0].query

    def test_spend_alert_uses_derived_threshold(self) -> None:
        t = self.registry.thresholds
        spend_alerts = [a for a in self.alerts if "spend" in a.name.lower()]
        assert len(spend_alerts) == 1
        assert spend_alerts[0].thresholds["critical"] == pytest.approx(t.spend_critical_usd)

    def test_all_alerts_have_service_tag(self) -> None:
        for alert in self.alerts:
            assert "service:ai-driven" in alert.tags, f"{alert.name} missing service tag"

    def test_all_alerts_have_env_tag(self) -> None:
        for alert in self.alerts:
            assert "env:test" in alert.tags, f"{alert.name} missing env tag"

    def test_build_result_is_cached(self) -> None:
        first = self.registry.build()
        second = self.registry.build()
        assert first is second


class TestAlertRegistryForEnv:
    def test_for_env_uses_given_env(self) -> None:
        registry = AlertRegistry.for_env("staging")
        assert registry.env == "staging"

    def test_for_env_uses_dd_env_when_not_specified(self, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.setenv("DD_ENV", "production")
        registry = AlertRegistry.for_env()
        assert registry.env == "production"


class TestAlertRegistryDryRun:
    def test_dry_run_prints_without_raising(self, capsys: pytest.CaptureFixture) -> None:
        registry = AlertRegistry(env="test")
        registry.dry_run()
        output = capsys.readouterr().out
        assert "P1" in output
        assert "DLQ" in output

    def test_dry_run_outputs_valid_json_blocks(self, capsys: pytest.CaptureFixture) -> None:
        registry = AlertRegistry(env="test")
        registry.dry_run()
        output = capsys.readouterr().out
        # Each block between "---" headers should contain a JSON object
        for block in output.split("---"):
            block = block.strip()
            if block.startswith("{"):
                json.loads(block)  # should not raise


class TestAlertRegistryApply:
    def test_apply_raises_if_api_client_missing(self) -> None:
        registry = AlertRegistry(env="test")
        with patch.dict("sys.modules", {"datadog_api_client": None}):
            with pytest.raises((ImportError, RuntimeError)):
                registry.apply()

    def test_apply_calls_create_monitor_for_each_alert(self, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.setenv("DD_API_KEY", "test-api-key")
        monkeypatch.setenv("DD_APP_KEY", "test-app-key")

        mock_monitor_result = MagicMock()
        mock_monitor_result.id = 123
        mock_monitor_result.to_dict.return_value = {"id": 123, "name": "test"}

        mock_api_instance = MagicMock()
        mock_api_instance.create_monitor.return_value = mock_monitor_result

        mock_monitors_api_cls = MagicMock(return_value=mock_api_instance)

        mock_monitor_cls = MagicMock(return_value=MagicMock())
        mock_options_cls = MagicMock(return_value=MagicMock())
        mock_thresholds_cls = MagicMock(return_value=MagicMock())

        mock_api_client_instance = MagicMock()
        mock_api_client_instance.__enter__ = MagicMock(return_value=mock_api_client_instance)
        mock_api_client_instance.__exit__ = MagicMock(return_value=False)
        mock_api_client_cls = MagicMock(return_value=mock_api_client_instance)

        mock_configuration = MagicMock()
        mock_configuration_cls = MagicMock(return_value=mock_configuration)

        modules = {
            "datadog_api_client": MagicMock(
                ApiClient=mock_api_client_cls,
                Configuration=mock_configuration_cls,
            ),
            "datadog_api_client.v1": MagicMock(),
            "datadog_api_client.v1.api": MagicMock(),
            "datadog_api_client.v1.api.monitors_api": MagicMock(MonitorsApi=mock_monitors_api_cls),
            "datadog_api_client.v1.model": MagicMock(),
            "datadog_api_client.v1.model.monitor": MagicMock(Monitor=mock_monitor_cls),
            "datadog_api_client.v1.model.monitor_options": MagicMock(MonitorOptions=mock_options_cls),
            "datadog_api_client.v1.model.monitor_thresholds": MagicMock(MonitorThresholds=mock_thresholds_cls),
        }

        with patch.dict("sys.modules", modules):
            registry = AlertRegistry(env="test")
            results = registry.apply()

        expected_count = len(registry.build())
        assert mock_api_instance.create_monitor.call_count == expected_count
        assert len(results) == expected_count


# ---------------------------------------------------------------------------
# Custom threshold overrides
# ---------------------------------------------------------------------------

class TestCustomThresholds:
    def test_custom_thresholds_reflected_in_queries(self) -> None:
        custom = AlertThresholds.__new__(AlertThresholds)
        object.__setattr__(custom, "error_rate_critical", 0.05)
        object.__setattr__(custom, "error_rate_warning", 0.025)
        object.__setattr__(custom, "latency_critical_ms", 20_000)
        object.__setattr__(custom, "latency_warning_ms", 10_000)
        object.__setattr__(custom, "spend_critical_fraction", 0.9)
        object.__setattr__(custom, "spend_warning_fraction", 0.7)
        object.__setattr__(custom, "queue_depth_critical", 200)
        object.__setattr__(custom, "queue_depth_warning", 100)
        object.__setattr__(custom, "daily_budget_usd", 100.0)

        registry = AlertRegistry(env="prod", thresholds=custom)
        alerts = registry.build()

        error_alert = next(a for a in alerts if "error rate" in a.name.lower())
        assert error_alert.thresholds["critical"] == pytest.approx(0.05)

        latency_alert = next(a for a in alerts if "latency" in a.name.lower())
        assert latency_alert.thresholds["critical"] == pytest.approx(20_000.0)

        spend_alert = next(a for a in alerts if "spend" in a.name.lower())
        # 90% of $100 = $90
        assert spend_alert.thresholds["critical"] == pytest.approx(90.0)
