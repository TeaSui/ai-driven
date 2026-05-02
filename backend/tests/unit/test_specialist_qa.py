"""Unit tests for QaSpecialist with mocked Claude.

Four invariants locked here (mirrors the T8/T9/T10 test shape):

  1. Happy path — Claude returns verdict='pass' with a schema-correct
     QaPayload (checks_performed + regression_risk='low'). The returned
     Artifact round-trips role/verdict/payload/tokens and
     regression_risk passes through verbatim.
  2. Block path — Claude returns verdict='block' with a failed
     checks_performed entry (unsatisfied acceptance_criterion) and a
     populated test_coverage_gap. Asserts the block flows through to
     ``art.verdict`` and the coverage gap surfaces in the payload. QA
     is the third live specialist allowed to block (after Security);
     the path must work end-to-end.
  3. Tool registration — QA exposes EXACTLY
     ``["github.get_pr_diff", "repo.read_file", "repo.overview"]`` in
     that order (revision §5.1). A regression that added or reordered
     tools would silently widen the gate's surface — this test fails
     loudly in that case.
  4. User-prompt envelope — ``_build_user_prompt`` MUST route through
     ``_build_initial_message`` so revision §5's XML envelope mandate
     can't be silently bypassed by a subclass rewrite. Checks envelope
     tags AND the role-specific <task> trailer.

Fixture ``_ctx()`` builds a :class:`SpecialistContext` with BA, security,
AND stack_analyst artifacts in ``prior_artifacts`` — QA is the first
specialist with a fan-in of three, so the test must cover that shape or
it doesn't meaningfully exercise the real dispatch.

The ``_claude_returns`` helper matches T8/T9/T10: strips the prefill
``{`` from the serialised body and sets ``stop_reason='stop_sequence'``
to clear the T6 base-class truncation guard.
"""
from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.agents.specialists.base import SpecialistContext
from ai_driven.agents.specialists.qa.agent import QaSpecialist


def _ba_artifact() -> Artifact:
    return Artifact(
        role="business_analyst",
        verdict="pass",
        reasoning="clear login flow",
        payload={
            "ambiguities_resolved": [],
            "acceptance_criteria": [
                {
                    "given": "valid creds",
                    "when": "POST /auth/login",
                    "then": "returns 200 with JWT",
                },
                {
                    "given": "wrong password",
                    "when": "POST /auth/login",
                    "then": "returns 401 invalid_credentials",
                },
            ],
            "out_of_scope": [],
            "blocking_questions": [],
        },
        model_used="claude-sonnet",
        input_tokens=100,
        output_tokens=50,
    )


def _sa_artifact() -> Artifact:
    return Artifact(
        role="stack_analyst",
        verdict="pass",
        reasoning="pure backend login endpoint",
        payload={
            "stacks": ["backend"],
            "primary_stack": "backend",
            "cross_cutting_concerns": [],
            "reasoning": "FastAPI handler only.",
        },
        model_used="claude-sonnet",
        input_tokens=80,
        output_tokens=40,
    )


def _sec_artifact() -> Artifact:
    return Artifact(
        role="security",
        verdict="pass",
        reasoning="standard login threat model",
        payload={
            "threat_model": [
                {
                    "stride_category": "Spoofing",
                    "threat": "Credential stuffing",
                    "severity": "high",
                    "mitigation": "Per-IP rate limit.",
                },
            ],
            "implementation_rules": [
                "Hash passwords with argon2id, parameters t=3 m=65536 p=1.",
                "Use parameterised queries for the email lookup.",
            ],
            "compliance_notes": [],
            "blocking_concerns": [],
        },
        model_used="claude-sonnet",
        input_tokens=200,
        output_tokens=150,
    )


def _ctx(description: str = "Add POST /auth/login returning a JWT.") -> SpecialistContext:
    """Build a SpecialistContext fixture with all three prior artifacts.

    QA fans in across BA + security at minimum; stack_analyst is
    included here too to exercise the "reads all prior" shape per task
    spec.
    """
    return SpecialistContext(
        task_id="t-qa",
        ticket={
            "key": "KAN-42",
            "summary": "Login endpoint",
            "description": description,
            "labels": ["backend", "auth"],
            "acceptance_criteria": [],
        },
        repo={
            "full_name": "org/repo",
            "scm_type": "github",
            "default_branch": "main",
        },
        tier="full_workflow",
        prior_artifacts={
            "business_analyst": _ba_artifact(),
            "stack_analyst": _sa_artifact(),
            "security": _sec_artifact(),
        },
    )


def _claude_returns(
    payload: dict,
    verdict: str = "pass",
    reasoning: str = "ok",
    stop_reason: str = "stop_sequence",
) -> AsyncMock:
    """Build an AsyncMock returning a MessageResponse-shaped namespace.

    Matches the T8/T9/T10 helper: strips the prefill ``{`` off the
    serialised body (the base re-attaches it) and defaults stop_reason
    to ``stop_sequence`` so the T6 truncation guard doesn't reject the
    output.
    """
    body = {"verdict": verdict, "reasoning": reasoning, "payload": payload}
    text = json.dumps(body)[1:]  # drop leading "{" — prefill provides it
    return AsyncMock(return_value=SimpleNamespace(
        text=text + "\n",
        stop_reason=stop_reason,
        usage=SimpleNamespace(input_tokens=300, output_tokens=200),
    ))


@pytest.mark.asyncio
async def test_qa_pass_when_all_checks_passed() -> None:
    agent = QaSpecialist()
    agent._publish_event = AsyncMock()  # type: ignore[method-assign]
    agent._claude.chat = _claude_returns(  # type: ignore[method-assign]
        {
            "checks_performed": [
                {
                    "check": "AC: 200 with JWT on valid creds",
                    "passed": True,
                    "detail": "test_login_happy_path asserts status=200.",
                },
                {
                    "check": "AC: 401 invalid_credentials on wrong password",
                    "passed": True,
                    "detail": "test_login_wrong_password asserts 401.",
                },
                {
                    "check": "Rule: argon2id with t=3 m=65536 p=1",
                    "passed": True,
                    "detail": "src/auth/hash.py parameters verified.",
                },
            ],
            "test_coverage_gap": [],
            "regression_risk": "low",
        },
        reasoning="All ACs covered; all security rules verified.",
    )

    art = await agent.run_specialist(_ctx())

    assert art.role == "qa"
    assert art.verdict == "pass"
    assert art.payload["regression_risk"] == "low"
    assert len(art.payload["checks_performed"]) == 3
    assert all(c["passed"] for c in art.payload["checks_performed"])
    assert art.payload["test_coverage_gap"] == []
    assert art.input_tokens == 300
    assert art.output_tokens == 200


@pytest.mark.asyncio
async def test_qa_block_when_ac_unsatisfied() -> None:
    """Block path: AC has no evidence → block with populated coverage gap."""
    agent = QaSpecialist()
    agent._publish_event = AsyncMock()  # type: ignore[method-assign]
    agent._claude.chat = _claude_returns(  # type: ignore[method-assign]
        {
            "checks_performed": [
                {
                    "check": "AC: 200 with JWT on valid creds",
                    "passed": True,
                    "detail": "happy path test exists.",
                },
                {
                    "check": "AC: 401 invalid_credentials on wrong password",
                    "passed": False,
                    "detail": "No test covers the wrong-password branch.",
                },
            ],
            "test_coverage_gap": [
                "No test for 401 invalid_credentials path on POST /auth/login.",
            ],
            "regression_risk": "medium",
        },
        verdict="block",
        reasoning="401 AC unverified; unmergeable.",
    )

    art = await agent.run_specialist(_ctx())

    assert art.verdict == "block"
    # A failed check entry MUST be present for a legitimate block —
    # blocking without a concrete failing check is exactly the
    # anti-pattern the prompt forbids.
    failed_checks = [
        c for c in art.payload["checks_performed"] if not c["passed"]
    ]
    assert len(failed_checks) >= 1
    assert any(
        "wrong-password" in c["detail"] or "401" in c["check"]
        for c in failed_checks
    )
    # test_coverage_gap MUST be populated on a block driven by missing
    # coverage — empty here would mean the block has no actionable fix.
    assert len(art.payload["test_coverage_gap"]) >= 1
    assert art.payload["regression_risk"] in ("low", "medium", "high")


def test_qa_registers_expected_tools() -> None:
    """Locks revision §5.1: QA exposes exactly three tools, in order.

    QA owns THREE read-only tools — ``github.get_pr_diff``,
    ``repo.read_file``, ``repo.overview``. The original plan listed
    ``scm.read_diff`` and ``tests.list_run_results``; revision §5.1
    corrected those to the valid names. A regression that reverted to
    the invalid names, or that added a write tool, would silently
    widen the gate's surface — this test fails loudly in that case.
    """
    agent = QaSpecialist()
    assert agent._get_tool_names() == [
        "github.get_pr_diff",
        "repo.read_file",
        "repo.overview",
    ]


def test_qa_user_prompt_contains_xml_envelope() -> None:
    """_build_user_prompt must route through _build_initial_message.

    Revision §5 mandates every specialist's user prompt start with the
    base-class XML envelope so user-content escaping isn't bypassed. A
    subclass that inlined json.dumps of the ticket/repo/prior-artifacts
    (the pre-revision shape from the plan) would fail this test by
    missing one or more of the envelope tags — which is exactly the
    regression we want to block.
    """
    agent = QaSpecialist()
    prompt = agent._build_user_prompt(_ctx())

    # Envelope tags produced by _build_initial_message.
    assert "<task_role>" in prompt
    assert "<jira_ticket>" in prompt
    assert "<repository>" in prompt
    assert "<prior_artifacts>" in prompt

    # Role-specific trailing instruction appended by this specialist.
    assert "<task>" in prompt
    assert "</task>" in prompt
    assert "acceptance_criteria" in prompt
    assert "implementation_rule" in prompt
    assert "regression_risk" in prompt

    # Every prior artifact (BA, stack_analyst, security) should render
    # inside <prior_artifacts>. QA is the first specialist with a
    # fan-in of three — locking this here keeps the envelope honest.
    assert "business_analyst" in prompt
    assert "stack_analyst" in prompt
    assert "security" in prompt
