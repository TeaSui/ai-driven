"""Pure-function composer that injects specialist artifacts into the
CodingAgent system prompt.

T18 — Phase 2.

Contract
--------
``compose_coding_prompt`` is **pure**: no DB access, no network I/O, no
async work, no mutation of its inputs. Given the same ``base_system_prompt``
and ``prior_artifacts`` it returns byte-identical output. That purity is
load-bearing — the orchestrator will call this inline from the CodingAgent
hot path, and the function must be safe to invoke repeatedly (e.g., in
fix-rounds) without leaking side effects.

We currently know how to inject two specialist roles:

* ``business_analyst`` → ``<acceptance_criteria>`` block built from
  ``payload["acceptance_criteria"]`` (list of ``{given, when, then}`` dicts).
* ``security`` → ``<security_rules>`` block built from
  ``payload["implementation_rules"]`` (list of strings).

``stack_analyst``, ``tech_lead``, and ``qa`` are reserved for Phase 3 — when
they appear we log at DEBUG and skip. Unknown roles and malformed payloads
are logged (WARNING for malformed, DEBUG for deferred) and skipped so one
bad artifact never blocks the rest of the composition.

Block order is stable so caching keys remain deterministic across calls:
acceptance_criteria first, security_rules second. Future roles get fixed
positions too.
"""
from __future__ import annotations

import logging
from typing import Any

from ai_driven.agents.orchestrator.artifacts import Artifact

logger = logging.getLogger(__name__)

# Roles we explicitly know about but don't inject yet. Kept separate from
# "truly unknown" so we can log at DEBUG (expected) instead of WARNING
# (probable bug).
_DEFERRED_ROLES: frozenset[str] = frozenset({"stack_analyst", "tech_lead", "qa"})


def compose_coding_prompt(
    base_system_prompt: str,
    prior_artifacts: dict[str, Artifact],
) -> str:
    """Return ``base_system_prompt`` with specialist-artifact XML blocks appended.

    Keys of ``prior_artifacts`` are role strings matching ``Artifact.role``
    values: ``"business_analyst"``, ``"security"``, ``"stack_analyst"``,
    ``"tech_lead"``, ``"qa"``.

    Unknown roles and malformed artifacts are skipped with a logged warning.
    If ``prior_artifacts`` is empty or contains no role we know how to
    inject, the function returns ``base_system_prompt`` unchanged (no
    trailing whitespace or empty blocks).
    """
    if not prior_artifacts:
        return base_system_prompt

    blocks: list[str] = []

    # Stable order: acceptance_criteria first, security_rules second.
    # Do NOT iterate ``prior_artifacts`` directly — dict ordering depends on
    # insertion order at the caller, which we don't control.
    ba = prior_artifacts.get("business_analyst")
    if ba is not None:
        block = _build_acceptance_criteria_block(ba)
        if block is not None:
            blocks.append(block)

    sec = prior_artifacts.get("security")
    if sec is not None:
        block = _build_security_rules_block(sec)
        if block is not None:
            blocks.append(block)

    # Deferred / unknown roles: log and skip, never raise.
    for role in prior_artifacts:
        if role in ("business_analyst", "security"):
            continue
        if role in _DEFERRED_ROLES:
            logger.debug(
                "prompt_composer: role %r is deferred to Phase 3; skipping injection",
                role,
            )
        else:
            logger.warning(
                "prompt_composer: unknown artifact role %r; skipping injection",
                role,
            )

    if not blocks:
        return base_system_prompt

    return base_system_prompt + "\n\n" + "\n\n".join(blocks)


# ---------------------------------------------------------------------------
# Per-role block builders
# ---------------------------------------------------------------------------


def _build_acceptance_criteria_block(artifact: Artifact) -> str | None:
    """Build the ``<acceptance_criteria>`` block, or return None to skip.

    Skip (with a log) when:
      * ``payload`` is not a dict;
      * ``acceptance_criteria`` is missing, not a list, or empty;
      * any entry is missing ``given`` / ``when`` / ``then`` or isn't a dict.
    """
    payload = artifact.payload
    if not isinstance(payload, dict):
        logger.warning(
            "prompt_composer: business_analyst payload is not a dict (got %s); skipping",
            type(payload).__name__,
        )
        return None

    criteria: Any = payload.get("acceptance_criteria")
    if criteria is None:
        logger.debug(
            "prompt_composer: business_analyst payload has no acceptance_criteria; skipping",
        )
        return None
    if not isinstance(criteria, list):
        logger.warning(
            "prompt_composer: business_analyst acceptance_criteria is %s, expected list; skipping",
            type(criteria).__name__,
        )
        return None
    if len(criteria) == 0:
        logger.debug(
            "prompt_composer: business_analyst acceptance_criteria list is empty; skipping",
        )
        return None

    lines: list[str] = []
    for idx, entry in enumerate(criteria):
        if not isinstance(entry, dict):
            logger.warning(
                "prompt_composer: business_analyst acceptance_criteria[%d] is %s, expected dict; skipping block",
                idx,
                type(entry).__name__,
            )
            return None
        try:
            given = entry["given"]
            when = entry["when"]
            then = entry["then"]
        except KeyError as missing:
            logger.warning(
                "prompt_composer: business_analyst acceptance_criteria[%d] missing key %s; skipping block",
                idx,
                missing,
            )
            return None
        lines.append(f"- Given {given}, when {when}, then {then}")

    body = "\n".join(lines)
    return (
        "<acceptance_criteria>\n"
        "The following acceptance criteria were produced by the BusinessAnalyst\n"
        "specialist. You MUST satisfy every one.\n"
        f"{body}\n"
        "</acceptance_criteria>"
    )


def _build_security_rules_block(artifact: Artifact) -> str | None:
    """Build the ``<security_rules>`` block, or return None to skip.

    If ``blocking_concerns`` is non-empty the orchestrator shouldn't have
    dispatched CodingAgent at all — log a warning but still emit the rules
    block so the coding run at least has the rules in context.
    """
    payload = artifact.payload
    if not isinstance(payload, dict):
        logger.warning(
            "prompt_composer: security payload is not a dict (got %s); skipping",
            type(payload).__name__,
        )
        return None

    rules: Any = payload.get("implementation_rules")
    if rules is None:
        logger.debug(
            "prompt_composer: security payload has no implementation_rules; skipping",
        )
        return None
    if not isinstance(rules, list):
        logger.warning(
            "prompt_composer: security implementation_rules is %s, expected list; skipping",
            type(rules).__name__,
        )
        return None
    if len(rules) == 0:
        logger.debug(
            "prompt_composer: security implementation_rules list is empty; skipping",
        )
        return None
    for idx, rule in enumerate(rules):
        if not isinstance(rule, str):
            logger.warning(
                "prompt_composer: security implementation_rules[%d] is %s, expected str; skipping block",
                idx,
                type(rule).__name__,
            )
            return None

    blocking = payload.get("blocking_concerns")
    if isinstance(blocking, list) and len(blocking) > 0:
        logger.warning(
            "prompt_composer: security payload has %d blocking_concerns — "
            "CodingAgent should not have been dispatched. Emitting rules block anyway.",
            len(blocking),
        )

    numbered = "\n".join(f"{i + 1}. {rule}" for i, rule in enumerate(rules))
    return (
        "<security_rules>\n"
        "The following implementation rules were produced by the Security\n"
        "specialist. You MUST follow every rule verbatim; QA will verify.\n"
        f"{numbered}\n"
        "</security_rules>"
    )
