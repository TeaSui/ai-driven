"""Tier classifier — single fast-model call per task (Sonnet post-H1).

Revision deltas vs. the pure plan version:
  * ``max_tokens=16`` (was 8) — a couple of short tokens of slack lets
    models that accidentally emit a leading space / newline still return
    a parseable tier word; the red-team block flagged 8 as "one bad
    tokenizer boundary away from silent truncation to 'full_wo'".
  * ``parse_tier`` strips a broader punctuation set so stray quotes,
    periods, and trailing whitespace don't fall through to the safe
    default.
  * The safe-default fallback (infra error OR unknown-token parse) is
    logged as a warning. ``TierClassifier`` is not a :class:`BaseAgent`
    subclass so it has no ``_publish_event`` hook — the log line is the
    sole observability signal.
"""
from __future__ import annotations

import json
import logging
from typing import Any

from ai_driven.claude.client import ClaudeClient
from ai_driven.claude.prompts.orchestrator import TIER_CLASSIFIER_SYSTEM_PROMPT
from ai_driven.config import get_settings

logger = logging.getLogger(__name__)

_VALID_TIERS = {"fast_path", "full_workflow", "multi_domain"}
_DEFAULT_TIER = "full_workflow"   # safe default on parse/infra failure
_STRIP_CHARS = "`\"'.,\n\t "      # red-team: drop stray punctuation + ws


def parse_tier(text: str) -> str:
    """Extract the tier word from raw model output. Defaults to full_workflow."""
    if not text:
        return _DEFAULT_TIER
    cleaned = text.strip(_STRIP_CHARS)
    # First whitespace-separated token, lowercased, with punctuation trimmed
    token = cleaned.split()[0].lower().strip(_STRIP_CHARS) if cleaned else ""
    if token in _VALID_TIERS:
        return token
    logger.warning(
        "orchestrator.tier_fallback: unrecognized tier token %r; "
        "falling back to %s", token, _DEFAULT_TIER,
    )
    return _DEFAULT_TIER


class TierClassifier:
    """Single-call classifier (settings.claude_fast_model) returning {fast_path,full_workflow,multi_domain}."""

    def __init__(self, claude_client: ClaudeClient | None = None) -> None:
        self._claude = claude_client or ClaudeClient()

    async def classify(
        self,
        ticket: dict[str, Any],
        repo: dict[str, Any],
    ) -> str:
        settings = get_settings()
        user = (
            "## Ticket\n"
            f"{json.dumps(ticket, indent=2)}\n\n"
            "## Repo\n"
            f"{json.dumps(repo, indent=2)}\n\n"
            "Output exactly one word: fast_path | full_workflow | multi_domain"
        )
        try:
            resp = await self._claude.chat(
                messages=[{"role": "user", "content": user}],
                system=TIER_CLASSIFIER_SYSTEM_PROMPT,
                model=settings.claude_fast_model,
                max_tokens=16,
                temperature=0.0,
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "orchestrator.tier_fallback: classifier infra error (%s); "
                "falling back to %s", exc, _DEFAULT_TIER,
            )
            return _DEFAULT_TIER
        return parse_tier(resp.text or "")
