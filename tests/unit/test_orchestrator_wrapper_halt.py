"""Unit tests for T21 strict-gating halt predicate + blocking-reasons helper.

Covers the pure, DB-free surface of the orchestrator wrapper:
  * ``_should_halt_pipeline`` — 5 conditions must all be true to halt.
  * ``_extract_blocking_reasons`` — per-role payload dispatch with a
    reasoning fallback that ALWAYS returns at least one string.

Integration coverage (wrapper + DB + producer wiring) lives in
``tests/integration/test_orchestrator_worker.py``.
"""
from __future__ import annotations

from types import SimpleNamespace

import pytest

from ai_driven.queue.consumer import (
    _extract_blocking_reasons,
    _should_halt_pipeline,
)


# ── _should_halt_pipeline ───────────────────────────────────────────────────


def test_halt_when_live_block_non_qa() -> None:
    """Canonical halt: live mode + non-qa block."""
    result = {
        "halted_on": "security",
        "artifacts": {"security": "block"},
    }
    assert _should_halt_pipeline(result, stamped_mode="live") is True


def test_no_halt_in_shadow_mode() -> None:
    """Shadow mode preserves pre-T21 behaviour — never halts."""
    result = {
        "halted_on": "security",
        "artifacts": {"security": "block"},
    }
    assert _should_halt_pipeline(result, stamped_mode="shadow") is False


def test_no_halt_when_mode_off() -> None:
    """Explicit 'off' also never halts."""
    result = {
        "halted_on": "security",
        "artifacts": {"security": "block"},
    }
    assert _should_halt_pipeline(result, stamped_mode="off") is False


def test_no_halt_when_no_halted_on() -> None:
    """Clean run → no halt regardless of mode."""
    result = {"halted_on": None, "artifacts": {}}
    assert _should_halt_pipeline(result, stamped_mode="live") is False


def test_no_halt_on_qa_even_in_live_mode() -> None:
    """Pre-coding QA is the final_gate in routing.py and must NEVER halt.

    T22 splits pre- and post-coding QA. Until then, letting QA block would
    halt every full_workflow / multi_domain task (no PR yet exists).
    """
    result = {
        "halted_on": "qa",
        "artifacts": {"qa": "block"},
    }
    assert _should_halt_pipeline(result, stamped_mode="live") is False


def test_no_halt_on_error_verdict() -> None:
    """Strict-error mode sets halted_on for verdict=error. T21 must NOT halt.

    Otherwise a Bedrock flake silently becomes 'blocked' — a distinct,
    operator-visible status that implies human triage.
    """
    result = {
        "halted_on": "security",
        "artifacts": {"security": "error"},
    }
    assert _should_halt_pipeline(result, stamped_mode="live") is False


def test_no_halt_on_pass_with_notes() -> None:
    """halted_on should be None for pass-with-notes, but guard anyway."""
    result = {
        "halted_on": "security",
        "artifacts": {"security": "pass_with_notes"},
    }
    assert _should_halt_pipeline(result, stamped_mode="live") is False


def test_no_halt_when_artifacts_missing() -> None:
    """Defensive: missing artifacts key → no halt, no crash."""
    result = {"halted_on": "security"}
    assert _should_halt_pipeline(result, stamped_mode="live") is False


def test_halt_on_business_analyst_block() -> None:
    """BA block in live mode halts (regression: BA is non-qa)."""
    result = {
        "halted_on": "business_analyst",
        "artifacts": {"business_analyst": "block"},
    }
    assert _should_halt_pipeline(result, stamped_mode="live") is True


# ── _extract_blocking_reasons ───────────────────────────────────────────────


def _artifact(payload=None, reasoning=None):
    return SimpleNamespace(payload=payload or {}, reasoning=reasoning)


def test_blocking_reasons_business_analyst_uses_blocking_questions() -> None:
    art = _artifact(
        payload={"blocking_questions": ["Is the customer ID a uuid?",
                                        "What status on duplicate?"]},
        reasoning="ambiguous spec",
    )
    assert _extract_blocking_reasons("business_analyst", art) == [
        "Is the customer ID a uuid?",
        "What status on duplicate?",
    ]


def test_blocking_reasons_security_uses_blocking_concerns() -> None:
    art = _artifact(
        payload={"blocking_concerns": ["No auth on admin endpoint"]},
        reasoning="threat model incomplete",
    )
    assert _extract_blocking_reasons("security", art) == [
        "No auth on admin endpoint",
    ]


def test_blocking_reasons_stack_analyst_falls_back_to_reasoning() -> None:
    """stack_analyst has no dedicated blocker key — must fall back."""
    art = _artifact(payload={"stacks": ["backend"]},
                    reasoning="Stack mismatch with repo tags")
    assert _extract_blocking_reasons("stack_analyst", art) == [
        "Stack mismatch with repo tags",
    ]


def test_blocking_reasons_tech_lead_falls_back_to_reasoning() -> None:
    art = _artifact(reasoning="ADR missing rollback section")
    assert _extract_blocking_reasons("tech_lead", art) == [
        "ADR missing rollback section",
    ]


def test_blocking_reasons_ux_designer_falls_back_to_reasoning() -> None:
    art = _artifact(reasoning="flow leaves user stranded on error")
    assert _extract_blocking_reasons("ux_designer", art) == [
        "flow leaves user stranded on error",
    ]


def test_blocking_reasons_api_test_falls_back_to_reasoning() -> None:
    art = _artifact(reasoning="endpoint 500ing under 10rps")
    assert _extract_blocking_reasons("api_test", art) == [
        "endpoint 500ing under 10rps",
    ]


def test_blocking_reasons_empty_list_falls_back_to_reasoning() -> None:
    """Role-specific key present but EMPTY → fall back to reasoning.

    An empty list tells the operator nothing, so we treat it as absent.
    """
    art = _artifact(payload={"blocking_questions": []},
                    reasoning="unclear acceptance criteria")
    assert _extract_blocking_reasons("business_analyst", art) == [
        "unclear acceptance criteria",
    ]


def test_blocking_reasons_empty_everywhere_falls_back_to_sentinel() -> None:
    """No blocker key, no reasoning — emit a generic, non-empty sentinel.

    Callers (UI, admin) can rely on len(reasons) >= 1.
    """
    art = _artifact(payload={}, reasoning=None)
    out = _extract_blocking_reasons("tech_lead", art)
    assert len(out) == 1
    assert "tech_lead" in out[0]


def test_blocking_reasons_handles_none_artifact() -> None:
    """If the artifact row couldn't be loaded we still return a non-empty list."""
    out = _extract_blocking_reasons("security", None)
    assert len(out) == 1
    assert "security" in out[0]


def test_blocking_reasons_drops_falsy_entries() -> None:
    """Empty/None entries in the payload list are filtered out."""
    art = _artifact(
        payload={"blocking_questions": ["real question", "", None]},
    )
    assert _extract_blocking_reasons("business_analyst", art) == [
        "real question",
    ]
