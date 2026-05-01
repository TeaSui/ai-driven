"""Unit tests for the T23 pure QA-gate decision function.

Red-team constraints we assert here:

* Exhaustive per-verdict branches. Every one of ``VERDICTS`` must have
  its own explicit arm in ``decide_qa_gate`` -- no silent ``else``
  fallthrough. We enforce this structurally with ``test_no_else_fallthrough``
  and behaviourally by asserting each verdict routes to a distinct
  counter name.

* Shadow mode is a hard invariant: ``new_status`` MUST be ``None`` for
  every verdict when ``stamped_mode != 'live'``.

* ``_extract_failures`` filters:
    - AC with satisfied=True -> dropped
    - security rule with severity in {low, medium} -> dropped even when
      followed=False
    - security rule with severity in {high, critical} and followed=False
      -> included
"""
from __future__ import annotations

import inspect

import pytest

from ai_driven.agents.orchestrator.qa_gate import (
    QaGateDecision,
    VERDICTS,
    _extract_failures,
    decide_qa_gate,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _v2_empty_payload() -> dict:
    return {
        "schema_version": "v2",
        "checks_performed": [],
        "acceptance_criteria_verification": [],
        "security_rules_verification": [],
        "test_coverage_gap": [],
        "regression_risk": "low",
    }


def _v2_failing_payload() -> dict:
    return {
        "schema_version": "v2",
        "checks_performed": ["ac", "security"],
        "acceptance_criteria_verification": [
            {"criterion": "AC1 login rejects invalid email",
             "satisfied": False,
             "reason": "regex accepts 'a@b'",
             "evidence": "src/auth.py:42"},
            {"criterion": "AC2 password hashed with argon2id",
             "satisfied": True,
             "reason": "verified",
             "evidence": "src/auth.py:10"},
        ],
        "security_rules_verification": [
            {"rule": "store argon2id hash",
             "followed": False,
             "severity": "high",
             "evidence": "src/auth.py:10"},
            {"rule": "use SecureRandom for salt",
             "followed": False,
             "severity": "critical",
             "evidence": "src/auth.py:20"},
            {"rule": "prefer constant-time comparison",
             "followed": False,
             "severity": "medium",
             "evidence": "src/auth.py:30"},
            {"rule": "log masked email on failure",
             "followed": False,
             "severity": "low",
             "evidence": "src/auth.py:40"},
            {"rule": "csrf token present",
             "followed": True,
             "severity": "high",
             "evidence": "src/forms.py:5"},
        ],
        "test_coverage_gap": [],
        "regression_risk": "medium",
    }


# ---------------------------------------------------------------------------
# Live-mode branches
# ---------------------------------------------------------------------------


def test_live_pass_routes_to_completed():
    d = decide_qa_gate("pass", _v2_empty_payload(), "live")
    assert d == QaGateDecision(
        new_status="completed",
        qa_failures=[],
        counter_name="qa.gate.pass",
    )


def test_live_pass_with_notes_routes_to_completed():
    d = decide_qa_gate("pass_with_notes", _v2_empty_payload(), "live")
    assert d.new_status == "completed"
    assert d.qa_failures == []
    assert d.counter_name == "qa.gate.pass_with_notes"


def test_live_block_routes_to_qa_failed_with_failures():
    d = decide_qa_gate("block", _v2_failing_payload(), "live")
    assert d.new_status == "qa_failed"
    assert d.counter_name == "qa.gate.blocked"
    kinds = sorted(f["kind"] for f in d.qa_failures)
    # 1 unsatisfied AC + 2 high/critical unfollowed security rules.
    # medium/low severities dropped, AC2 dropped (satisfied=True), csrf
    # rule dropped (followed=True).
    assert kinds == ["ac_unsatisfied",
                     "security_rule_unfollowed",
                     "security_rule_unfollowed"]
    severities = sorted(
        f["severity"] for f in d.qa_failures
        if f["kind"] == "security_rule_unfollowed"
    )
    assert severities == ["critical", "high"]


def test_live_error_is_lenient_pass_through():
    d = decide_qa_gate("error", _v2_empty_payload(), "live")
    assert d.new_status == "completed"
    assert d.qa_failures == []
    assert d.counter_name == "qa.gate.lenient_error"


def test_live_not_implemented_is_lenient_pass_through():
    """B2 red-team fix: explicit branch, not a silent fallthrough."""
    d = decide_qa_gate("not_implemented", _v2_empty_payload(), "live")
    assert d.new_status == "completed"
    assert d.qa_failures == []
    assert d.counter_name == "qa.gate.not_implemented"


# ---------------------------------------------------------------------------
# Shadow mode
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("verdict", list(VERDICTS))
def test_shadow_mode_never_transitions_status(verdict):
    d = decide_qa_gate(verdict, _v2_failing_payload(), "shadow")
    assert d.new_status is None
    assert d.counter_name == f"qa.gate.shadow.{verdict}"


def test_shadow_block_still_extracts_failures_for_observability():
    d = decide_qa_gate("block", _v2_failing_payload(), "shadow")
    assert d.new_status is None
    assert len(d.qa_failures) == 3  # same 3 as live-block
    assert d.counter_name == "qa.gate.shadow.block"


def test_shadow_pass_has_no_failures():
    d = decide_qa_gate("pass", _v2_failing_payload(), "shadow")
    assert d.qa_failures == []
    assert d.counter_name == "qa.gate.shadow.pass"


def test_shadow_mode_unknown_stamp_still_shadow():
    """stamped_mode other than 'live' is treated as shadow (defensive)."""
    d = decide_qa_gate("block", _v2_failing_payload(), "")
    assert d.new_status is None
    d = decide_qa_gate("pass", _v2_empty_payload(), "something_else")
    assert d.new_status is None


# ---------------------------------------------------------------------------
# Unknown verdict safety
# ---------------------------------------------------------------------------


def test_unknown_verdict_does_not_transition_and_emits_signal():
    d = decide_qa_gate("totally_new_verdict", _v2_empty_payload(), "live")
    assert d.new_status is None, (
        "Unknown verdict must NOT silently transition -- the whole point "
        "of the explicit-branch rule is to catch schema drift."
    )
    assert d.counter_name == "qa.gate.unknown_verdict"


# ---------------------------------------------------------------------------
# Structural: no else fallthrough
# ---------------------------------------------------------------------------


def test_no_else_fallthrough_in_decide_qa_gate():
    """Guard against a future refactor collapsing branches into an else.

    Red-team wants each of the 5 verdicts handled explicitly. This source
    inspection catches a drift where someone collapses ``error`` and
    ``not_implemented`` into a shared else arm (which would regress B2).
    """
    src = inspect.getsource(decide_qa_gate)
    for v in VERDICTS:
        assert f'verdict == "{v}"' in src, (
            f"Expected explicit branch for verdict=={v!r} in decide_qa_gate"
        )


# ---------------------------------------------------------------------------
# _extract_failures
# ---------------------------------------------------------------------------


def test_extract_failures_drops_satisfied_acs():
    payload = {
        "acceptance_criteria_verification": [
            {"criterion": "AC1", "satisfied": True, "reason": "", "evidence": ""},
            {"criterion": "AC2", "satisfied": False, "reason": "gap",
             "evidence": "x.py:1"},
        ],
        "security_rules_verification": [],
    }
    failures = _extract_failures(payload)
    assert len(failures) == 1
    assert failures[0]["criterion"] == "AC2"
    assert failures[0]["kind"] == "ac_unsatisfied"


def test_extract_failures_drops_followed_security_rules():
    payload = {
        "acceptance_criteria_verification": [],
        "security_rules_verification": [
            {"rule": "r1", "followed": True, "severity": "critical",
             "evidence": "x.py:1"},
            {"rule": "r2", "followed": False, "severity": "high",
             "evidence": "x.py:2"},
        ],
    }
    failures = _extract_failures(payload)
    assert len(failures) == 1
    assert failures[0]["rule"] == "r2"


@pytest.mark.parametrize("severity", ["low", "medium", "info", ""])
def test_extract_failures_drops_low_and_medium_severity(severity):
    """Only high/critical unfollowed security rules reach qa_failures."""
    payload = {
        "acceptance_criteria_verification": [],
        "security_rules_verification": [
            {"rule": "r", "followed": False, "severity": severity,
             "evidence": "x.py:1"},
        ],
    }
    assert _extract_failures(payload) == []


@pytest.mark.parametrize("severity", ["high", "critical", "High", "CRITICAL"])
def test_extract_failures_includes_high_and_critical(severity):
    payload = {
        "acceptance_criteria_verification": [],
        "security_rules_verification": [
            {"rule": "r", "followed": False, "severity": severity,
             "evidence": "x.py:1"},
        ],
    }
    failures = _extract_failures(payload)
    assert len(failures) == 1
    assert failures[0]["severity"] == severity.lower()


def test_extract_failures_tolerates_missing_keys():
    """Defensive: payload shape drift shouldn't raise KeyError."""
    assert _extract_failures({}) == []
    assert _extract_failures({"acceptance_criteria_verification": None,
                              "security_rules_verification": None}) == []
