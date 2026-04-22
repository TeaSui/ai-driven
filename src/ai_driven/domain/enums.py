"""Canonical enumerations shared across the ai-driven platform."""

from __future__ import annotations

from enum import Enum


class IncidentSeverity(str, Enum):
    """Canonical incident severity levels.

    Values are **always** uppercase: P1, P2, P3, P4.
    Use :meth:`normalize` to coerce user input into the canonical form.
    """

    P1 = "P1"
    P2 = "P2"
    P3 = "P3"
    P4 = "P4"

    @classmethod
    def normalize(cls, value: str | None) -> "IncidentSeverity | None":
        """Convert a raw severity string to the canonical enum member.

        Handles case-insensitive matching and strips whitespace.
        Returns ``None`` for unrecognised or ``None`` input.
        """
        if value is None:
            return None
        upper = value.strip().upper()
        return cls(upper) if upper in cls._value2member_map_ else None


class TeamRole(str, Enum):
    """Role-based access control roles for team members.

    Privilege hierarchy (highest to lowest):
      OWNER  — full access including team deletion and data purge
      ADMIN  — manage repos, rotate keys, manage members
      MEMBER — create tasks, view results
      VIEWER — read-only access
    """

    OWNER = "owner"
    ADMIN = "admin"
    MEMBER = "member"
    VIEWER = "viewer"

    @classmethod
    def normalize(cls, value: str | None) -> "TeamRole | None":
        """Convert a raw role string to the canonical enum member.

        Handles case-insensitive matching and strips whitespace.
        Returns ``None`` for unrecognised or ``None`` input.
        """
        if value is None:
            return None
        lower = value.strip().lower()
        return cls(lower) if lower in cls._value2member_map_ else None
