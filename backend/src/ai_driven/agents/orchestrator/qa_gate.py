"""T23 QA gate -- pure decision function bridging QA verdict to task status.

The QA specialist (T22) returns an Artifact with a ``verdict`` in:
``pass``, ``pass_with_notes``, ``block``, ``error``, ``not_implemented``.

T23 maps that verdict + the stamped orchestrator mode to a status
transition the caller should perform. Keeping the logic in a pure
function makes it trivially unit-testable and keeps all side effects
(DB writes, counters) in ``consumer.py``'s ``_persist_artifact_and_marker``
so a single commit persists both the artifact AND the status transition
atomically.

Contract (see ``docs/plans/t23-qa-verdict-status-brief.md`` section 2):

  Live mode:
    pass            -> completed,  counter qa.gate.pass
    pass_with_notes -> completed,  counter qa.gate.pass_with_notes
    block           -> qa_failed + qa_failures list, counter qa.gate.blocked
    error           -> completed,  counter qa.gate.lenient_error   (lenient pass-through)
    not_implemented -> completed,  counter qa.gate.not_implemented (lenient pass-through, B2)

  Shadow mode (stamped_mode != "live"):
    ANY verdict     -> new_status=None (do NOT transition), counter qa.gate.shadow.<verdict>
    block verdict still extracts qa_failures for observability.

Red-team constraints this module MUST satisfy:
  * Explicit branch for each of the 5 verdicts -- NO ``else`` fallthrough.
    Silent coverage of a renamed/added verdict is a correctness bug.
  * Pure function, no DB access, no counter emission. Caller emits the
    counter AFTER the DB commit.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


# The five verdicts QaSpecialist may emit. Kept as a module-level constant
# so tests can assert exhaustiveness without importing the Pydantic/Literal
# definition from the specialist module (which pulls in Claude/Bedrock
# clients as a side effect).
VERDICTS: tuple[str, ...] = (
    "pass",
    "pass_with_notes",
    "block",
    "error",
    "not_implemented",
)


@dataclass(frozen=True)
class QaGateDecision:
    """Resolution of the QA-verdict -> task-status gate.

    Attributes:
        new_status:    The status the caller should transition the task to.
                       ``None`` means "do not touch status" (shadow mode, or
                       an unknown verdict we refused to gate on).
        qa_failures:   Structured failure payload for
                       ``Task.output_data.qa_failures``. Non-empty only on
                       verdict=block. UI (T25) renders this as the
                       triage list.
        counter_name:  Counter the caller must emit AFTER the DB commit.
                       ``None`` is reserved for "no signal" (currently
                       unused; every branch names its counter).
    """

    new_status: str | None
    qa_failures: list[dict[str, Any]] = field(default_factory=list)
    counter_name: str | None = None


def decide_qa_gate(
    verdict: str,
    payload: dict[str, Any],
    stamped_mode: str,
) -> QaGateDecision:
    """Decide the status transition for a QA verdict.

    Args:
        verdict:      One of ``VERDICTS``. Unknown verdicts fall through
                      to a lenient-error branch and emit a dedicated
                      counter so ops can alert on schema drift.
        payload:      The QaPayloadV2 dict (i.e. ``artifact.payload``).
                      Used only on verdict=block to extract the
                      structured failures list.
        stamped_mode: The SQS-stamped ``orchestrator_mode`` -- either
                      ``"live"`` or ``"shadow"`` (default ``shadow`` when
                      absent). Shadow mode NEVER transitions status.

    Returns:
        A ``QaGateDecision`` the caller must apply.
    """
    # Shadow mode: never transition status; still extract failures on
    # block so the observability surface (output_data.qa_failures) is
    # populated for dashboards even without a state-machine transition.
    if stamped_mode != "live":
        failures = _extract_failures(payload) if verdict == "block" else []
        return QaGateDecision(
            new_status=None,
            qa_failures=failures,
            counter_name=f"qa.gate.shadow.{verdict}",
        )

    # Live mode. Explicit per-verdict branches -- NO else fallthrough
    # (red-team: we must not silently gate a verdict the specialist
    # may add later).
    if verdict == "pass":
        return QaGateDecision(
            new_status="completed",
            qa_failures=[],
            counter_name="qa.gate.pass",
        )
    if verdict == "pass_with_notes":
        return QaGateDecision(
            new_status="completed",
            qa_failures=[],
            counter_name="qa.gate.pass_with_notes",
        )
    if verdict == "block":
        return QaGateDecision(
            new_status="qa_failed",
            qa_failures=_extract_failures(payload),
            counter_name="qa.gate.blocked",
        )
    if verdict == "error":
        # Lenient pass-through. T22 forces verdict='error' on schema drift,
        # specialist exceptions, and missing pr_number -- none of which
        # should flip a successful coding run to qa_failed. Phase 2
        # plan line 50-51: error never blocks.
        return QaGateDecision(
            new_status="completed",
            qa_failures=[],
            counter_name="qa.gate.lenient_error",
        )
    if verdict == "not_implemented":
        # Explicit branch (B2 red-team fix). Current Phase 2 specialists
        # never emit this, but the Verdict Literal includes it and we
        # refuse to silent-fallthrough. Treated as lenient-pass.
        return QaGateDecision(
            new_status="completed",
            qa_failures=[],
            counter_name="qa.gate.not_implemented",
        )

    # Unknown verdict -- fail safe. Do NOT transition status; emit a
    # dedicated counter so ops can alert. We intentionally do NOT reuse
    # lenient_error here because that would mask a schema change.
    return QaGateDecision(
        new_status=None,
        qa_failures=[],
        counter_name="qa.gate.unknown_verdict",
    )


def _extract_failures(payload: dict[str, Any]) -> list[dict[str, Any]]:
    """Combine unsatisfied ACs + unfollowed high/critical security rules.

    Schema is stable across T23/T24/T25 -- UI consumers (T25) rely on it:
        [
          {kind: "ac_unsatisfied",        criterion, reason, evidence},
          {kind: "security_rule_unfollowed", rule, severity, evidence},
          ...
        ]

    Low/medium-severity security rules are NOT included -- those are
    warnings, not blockers. The gate only surfaces high/critical so the
    qa_failures list stays actionable.
    """
    failures: list[dict[str, Any]] = []

    for ac in payload.get("acceptance_criteria_verification", []) or []:
        # Default satisfied=True so a malformed entry doesn't silently
        # materialize as a failure (defensive; specialist schema should
        # always emit this key).
        if not ac.get("satisfied", True):
            failures.append({
                "kind": "ac_unsatisfied",
                "criterion": ac.get("criterion", ""),
                "reason": ac.get("reason", ""),
                "evidence": ac.get("evidence", ""),
            })

    for rule in payload.get("security_rules_verification", []) or []:
        followed = rule.get("followed", True)
        severity = (rule.get("severity") or "").lower()
        if not followed and severity in {"high", "critical"}:
            failures.append({
                "kind": "security_rule_unfollowed",
                "rule": rule.get("rule", ""),
                "severity": severity,
                "evidence": rule.get("evidence", ""),
            })

    return failures
