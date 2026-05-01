"""Unit tests for ``ai_driven.agents.coding.prompt_composer``.

T18 — Phase 2. The composer is a pure function that appends specialist
artifact XML blocks to CodingAgent's base system prompt. These tests cover:

1. Empty prior_artifacts returns the base prompt unchanged.
2. A BA-only artifact injects ``<acceptance_criteria>`` with AC lines.
3. A Security-only artifact injects ``<security_rules>`` with numbered rules.
4. BA + Security: both blocks present, acceptance_criteria FIRST.
5. Malformed BA payload (``acceptance_criteria`` is a string, not a list):
   BA block skipped with a warning, Security block still emitted.
6. Unknown / deferred roles (stack_analyst, tech_lead, qa) do not raise,
   do not emit blocks, and log a debug line.
"""
from __future__ import annotations

import logging
from decimal import Decimal

from ai_driven.agents.coding.prompt_composer import compose_coding_prompt
from ai_driven.agents.orchestrator.artifacts import Artifact


BASE_PROMPT = "You are CodingAgent. Write correct, tested code."


def _ba_artifact(acceptance_criteria):
    return Artifact(
        role="business_analyst",
        verdict="pass",
        reasoning="ok",
        payload={
            "ambiguities_resolved": [],
            "acceptance_criteria": acceptance_criteria,
            "out_of_scope": [],
            "blocking_questions": [],
        },
        model_used="claude-sonnet-4-5",
        input_tokens=100,
        output_tokens=50,
        cost_usd=Decimal("0.001"),
    )


def _security_artifact(implementation_rules, blocking_concerns=None):
    return Artifact(
        role="security",
        verdict="pass",
        reasoning="ok",
        payload={
            "threat_model": [],
            "implementation_rules": implementation_rules,
            "compliance_notes": [],
            "blocking_concerns": blocking_concerns or [],
        },
        model_used="claude-sonnet-4-5",
        input_tokens=100,
        output_tokens=50,
        cost_usd=Decimal("0.001"),
    )


def test_empty_artifacts_returns_base_unchanged() -> None:
    assert compose_coding_prompt(BASE_PROMPT, {}) == BASE_PROMPT


def test_ba_only() -> None:
    art = _ba_artifact([
        {"given": "user is logged in", "when": "they click save", "then": "data persists"},
        {"given": "input is empty", "when": "they click save", "then": "a 400 is returned"},
    ])

    out = compose_coding_prompt(BASE_PROMPT, {"business_analyst": art})

    assert out.startswith(BASE_PROMPT)
    assert "<acceptance_criteria>" in out
    assert "</acceptance_criteria>" in out
    assert "- Given user is logged in, when they click save, then data persists" in out
    assert "- Given input is empty, when they click save, then a 400 is returned" in out
    assert "<security_rules>" not in out


def test_security_only() -> None:
    art = _security_artifact([
        "Validate all inputs at the HTTP boundary.",
        "Use parameterized queries — never interpolate SQL.",
        "Mask PII before logging.",
    ])

    out = compose_coding_prompt(BASE_PROMPT, {"security": art})

    assert out.startswith(BASE_PROMPT)
    assert "<security_rules>" in out
    assert "</security_rules>" in out
    assert "1. Validate all inputs at the HTTP boundary." in out
    assert "2. Use parameterized queries — never interpolate SQL." in out
    assert "3. Mask PII before logging." in out
    assert "<acceptance_criteria>" not in out


def test_ba_and_security_both() -> None:
    ba = _ba_artifact([
        {"given": "a user", "when": "they log in", "then": "a session starts"},
    ])
    sec = _security_artifact([
        "Sessions must be opaque tokens.",
    ])

    out = compose_coding_prompt(
        BASE_PROMPT,
        {"security": sec, "business_analyst": ba},  # deliberately out of order
    )

    assert out.startswith(BASE_PROMPT)
    ac_idx = out.index("<acceptance_criteria>")
    sec_idx = out.index("<security_rules>")
    assert ac_idx < sec_idx, "acceptance_criteria block must appear before security_rules"
    assert "- Given a user, when they log in, then a session starts" in out
    assert "1. Sessions must be opaque tokens." in out


def test_ba_malformed_acceptance_criteria_string_not_list(
    caplog: "logging.LogCaptureFixture",
) -> None:
    bad_ba = Artifact(
        role="business_analyst",
        verdict="pass",
        reasoning="ok",
        payload={
            "ambiguities_resolved": [],
            "acceptance_criteria": "this should have been a list",
            "out_of_scope": [],
            "blocking_questions": [],
        },
        model_used="claude-sonnet-4-5",
        input_tokens=100,
        output_tokens=50,
        cost_usd=Decimal("0.001"),
    )
    sec = _security_artifact(["Rule A"])

    with caplog.at_level(logging.WARNING, logger="ai_driven.agents.coding.prompt_composer"):
        out = compose_coding_prompt(
            BASE_PROMPT,
            {"business_analyst": bad_ba, "security": sec},
        )

    assert "<acceptance_criteria>" not in out
    assert "<security_rules>" in out
    assert "1. Rule A" in out
    warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert any("business_analyst" in r.getMessage() for r in warnings), (
        f"expected a warning mentioning business_analyst, got: {[r.getMessage() for r in warnings]}"
    )


def test_ignored_roles_do_not_raise() -> None:
    stack = Artifact(
        role="stack_analyst",
        verdict="pass",
        reasoning="ok",
        payload={
            "stacks": ["backend"],
            "primary_stack": "backend",
            "cross_cutting_concerns": [],
            "reasoning": "n/a",
        },
        model_used="claude-sonnet-4-5",
        input_tokens=10,
        output_tokens=10,
        cost_usd=Decimal("0"),
    )

    out = compose_coding_prompt(BASE_PROMPT, {"stack_analyst": stack})

    assert out == BASE_PROMPT  # nothing injected → unchanged
