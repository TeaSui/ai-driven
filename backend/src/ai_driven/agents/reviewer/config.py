"""Review configuration loaded from team settings."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class ReviewConfig:
    """Per-team review configuration."""

    auto_review_enabled: bool = True
    auto_approve_enabled: bool = False
    review_on_update: bool = True
    ignore_paths: list[str] = field(default_factory=list)
    severity_threshold: str = "suggestion"  # suggestion, warning, critical
    custom_rules: list[str] = field(default_factory=list)
    max_comments: int = 20

    @classmethod
    def from_settings(cls, settings_json: dict[str, Any] | None) -> ReviewConfig:
        """Load review config from team settings_json."""
        if not settings_json:
            return cls()
        # Support both nested {"review": {...}} and flat {...} formats
        review = settings_json.get("review", settings_json)
        return cls(
            auto_review_enabled=review.get("auto_review_enabled", True),
            auto_approve_enabled=review.get("auto_approve_enabled", False),
            review_on_update=review.get("review_on_update", True),
            ignore_paths=review.get("ignore_paths", []),
            severity_threshold=review.get("severity_threshold", "suggestion"),
            custom_rules=review.get("custom_rules", []),
            max_comments=review.get("max_comments", 20),
        )

    def to_prompt_section(self) -> str:
        """Format config as XML for system prompt injection."""
        parts = ["<team_rules>"]
        if self.ignore_paths:
            parts.append(f"Ignore files matching: {', '.join(self.ignore_paths)}")
        if self.severity_threshold != "suggestion":
            parts.append(
                f"Only report issues at '{self.severity_threshold}' severity or above."
            )
        if self.custom_rules:
            parts.append("Custom review rules:")
            for rule in self.custom_rules:
                parts.append(f"- {rule}")
        parts.append(f"Maximum {self.max_comments} inline comments per review.")
        parts.append("</team_rules>")
        return "\n".join(parts)
