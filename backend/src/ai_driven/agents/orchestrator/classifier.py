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

# Label-driven tier floor. These signal trust-boundary risk (auth,
# payments, PII, external API surface) per the project's workflow
# routing rules. If any of these labels are present the tier is at
# least full_workflow — the classifier's one-word LLM output cannot
# downgrade it to fast_path.
_TRUST_BOUNDARY_LABELS = {
    "security", "auth", "authn", "authz",
    "payments", "payment", "billing",
    "pii", "gdpr", "privacy",
    "trust-boundary", "external-api",
}

# Engineering-domain labels. A ticket that crosses two or more domains
# in addition to touching a trust boundary is multi_domain.
_DOMAIN_LABELS = {
    "backend", "frontend", "mobile", "ios", "android",
    "infra", "infrastructure", "devops", "data", "ai",
}


def enforce_label_floor(tier: str, labels: list[str] | None) -> str:
    """Apply the trust-boundary + domain label floor to a classifier result.

    Pure, unit-testable. Runs AFTER the LLM returns a parsed tier so a
    model that underestimates risk (choosing fast_path for an auth
    ticket) gets corrected by the deterministic label signal.
    """
    if not labels:
        return tier
    norm = {str(label).strip().lower() for label in labels}
    touches_trust_boundary = bool(norm & _TRUST_BOUNDARY_LABELS)
    domain_hits = len(norm & _DOMAIN_LABELS)

    # Explicit "multi-domain" label is an operator override.
    if "multi-domain" in norm or "multi_domain" in norm:
        return "multi_domain"

    if not touches_trust_boundary:
        return tier

    if domain_hits >= 2:
        return "multi_domain"
    # Trust-boundary present but single-domain → at least full_workflow.
    if tier == "fast_path":
        return "full_workflow"
    return tier


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
            tier = _DEFAULT_TIER
        else:
            tier = parse_tier(resp.text or "")

        # Deterministic label floor — the LLM's single-word output
        # can underestimate risk (choosing fast_path for an auth/PII
        # ticket because the summary looked harmless). Labels are a
        # stronger, cheaper signal than free-text, so we apply them
        # AFTER the LLM call and never downgrade.
        labels = ticket.get("labels") if isinstance(ticket, dict) else None
        if isinstance(labels, list):
            adjusted = enforce_label_floor(tier, labels)
            if adjusted != tier:
                logger.info(
                    "orchestrator.tier_label_floor: %s -> %s (labels=%s)",
                    tier, adjusted, labels,
                )
            tier = adjusted
        return tier
