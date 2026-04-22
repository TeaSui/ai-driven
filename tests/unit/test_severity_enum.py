"""Unit tests for IncidentSeverity enum and normalization."""

from __future__ import annotations

import pytest

from ai_driven.domain.enums import IncidentSeverity


class TestIncidentSeverityEnum:
    """Verify enum values and membership."""

    def test_enum_values(self) -> None:
        assert IncidentSeverity.P1.value == "P1"
        assert IncidentSeverity.P2.value == "P2"
        assert IncidentSeverity.P3.value == "P3"
        assert IncidentSeverity.P4.value == "P4"

    def test_enum_is_str(self) -> None:
        assert isinstance(IncidentSeverity.P1, str)
        assert IncidentSeverity.P1 == "P1"

    def test_all_members(self) -> None:
        assert len(IncidentSeverity) == 4


class TestNormalize:
    """Verify normalize() handles all expected inputs."""

    @pytest.mark.parametrize(
        "raw,expected",
        [
            ("P1", IncidentSeverity.P1),
            ("P2", IncidentSeverity.P2),
            ("P3", IncidentSeverity.P3),
            ("P4", IncidentSeverity.P4),
            ("p1", IncidentSeverity.P1),
            ("p2", IncidentSeverity.P2),
            ("p3", IncidentSeverity.P3),
            ("p4", IncidentSeverity.P4),
            (" P1 ", IncidentSeverity.P1),
            (" p2\n", IncidentSeverity.P2),
        ],
    )
    def test_valid_values(self, raw: str, expected: IncidentSeverity) -> None:
        assert IncidentSeverity.normalize(raw) is expected

    @pytest.mark.parametrize(
        "raw",
        [
            "P0",
            "P5",
            "critical",
            "warning",
            "X",
            "",
            "high",
        ],
    )
    def test_invalid_values_return_none(self, raw: str) -> None:
        assert IncidentSeverity.normalize(raw) is None

    def test_none_input_returns_none(self) -> None:
        assert IncidentSeverity.normalize(None) is None

    def test_normalize_result_is_enum_member(self) -> None:
        result = IncidentSeverity.normalize("p1")
        assert result is not None
        assert isinstance(result, IncidentSeverity)
        assert result.value == "P1"
