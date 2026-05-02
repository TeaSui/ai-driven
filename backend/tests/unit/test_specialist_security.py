"""Unit tests for SecuritySpecialist with mocked Claude.

Three invariants locked here (mirrors the T8/T9 test shape):

  1. Happy path — Claude returns verdict='pass' with a schema-correct
     SecurityPayload (threat_model + implementation_rules populated).
     The returned Artifact round-trips role/verdict/payload/tokens and
     the implementation_rules pass through verbatim (they are consumed
     by CodingAgent verbatim, so any silent rewrite would be a
     regression).
  2. Block path — Claude returns verdict='block' with non-empty
     blocking_concerns for a flatly unsafe request. Asserts the block
     flows through to ``art.verdict`` and blocking_concerns surface in
     the payload (Security is the only live specialist in Phase 1 that
     can legitimately block, so the path must work end-to-end).
  3. User-prompt envelope — ``_build_user_prompt`` MUST route through
     ``_build_initial_message`` so revision §5's XML envelope mandate
     can't be silently bypassed by a subclass rewrite. Checks envelope
     tags AND the role-specific <task> trailer.

Fixture ``_ctx()`` builds a :class:`SpecialistContext` with BOTH BA and
stack_analyst artifacts in ``prior_artifacts`` — Security is the first
specialist with a fan-in of two, so the test must cover that shape or
it doesn't meaningfully exercise the real dispatch.

The ``_claude_returns`` helper matches T8/T9: strips the prefill ``{``
from the serialised body and sets ``stop_reason='stop_sequence'`` to
clear the T6 base-class truncation guard.
"""
from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.agents.specialists.base import SpecialistContext
from ai_driven.agents.specialists.security.agent import SecuritySpecialist


def _ba_artifact() -> Artifact:
    return Artifact(
        role="business_analyst",
        verdict="pass",
        reasoning="clear login flow",
        payload={
            "ambiguities_resolved": [
                {"question": "JWT lifetime?", "resolution": "1h default"}
            ],
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
            "out_of_scope": ["password reset"],
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
            "reasoning": "FastAPI handler only; no frontend / mobile.",
        },
        model_used="claude-sonnet",
        input_tokens=80,
        output_tokens=40,
    )


def _ctx(description: str = "Add POST /auth/login returning a JWT.") -> SpecialistContext:
    return SpecialistContext(
        task_id="t-sec",
        ticket={
            "key": "KAN-42",
            "summary": "Login endpoint",
            "description": description,
            "labels": ["backend", "auth"],
            "acceptance_criteria": [],
        },
        repo={"full_name": "org/repo", "scm_type": "github", "default_branch": "main"},
        tier="full_workflow",
        prior_artifacts={
            "business_analyst": _ba_artifact(),
            "stack_analyst": _sa_artifact(),
        },
    )


def _claude_returns(
    payload: dict,
    verdict: str = "pass",
    stop_reason: str = "stop_sequence",
) -> AsyncMock:
    """Build an AsyncMock returning a MessageResponse-shaped namespace.

    Matches the T8/T9 helper: strips the prefill ``{`` off the serialised
    body (the base re-attaches it) and defaults stop_reason to
    ``stop_sequence`` so the T6 truncation guard doesn't reject the
    output.
    """
    body = {"verdict": verdict, "reasoning": "ok", "payload": payload}
    text = json.dumps(body)[1:]  # drop leading "{" — prefill provides it
    return AsyncMock(return_value=SimpleNamespace(
        text=text + "\n",
        stop_reason=stop_reason,
        usage=SimpleNamespace(input_tokens=200, output_tokens=150),
    ))


@pytest.mark.asyncio
async def test_security_pass_with_rules() -> None:
    agent = SecuritySpecialist()
    agent._publish_event = AsyncMock()  # type: ignore[method-assign]
    agent._claude.chat = _claude_returns({  # type: ignore[method-assign]
        "threat_model": [
            {
                "stride_category": "Spoofing",
                "threat": "Credential stuffing against /auth/login",
                "severity": "high",
                "mitigation": "Per-IP + per-email rate limit; 429 after 5 failures in 60s.",
            },
            {
                "stride_category": "Information Disclosure",
                "threat": "Password leaked via logs",
                "severity": "high",
                "mitigation": "Never log the password field; generic 401 body on failure.",
            },
        ],
        "implementation_rules": [
            "Hash passwords with argon2id, parameters t=3 m=65536 p=1.",
            "Use parameterised queries for the email lookup.",
            "JWT signing key MUST come from the secrets manager; never hardcode.",
        ],
        "compliance_notes": ["PDPA: login attempt audit log entry for 401/429."],
        "blocking_concerns": [],
    })

    art = await agent.run_specialist(_ctx())

    assert art.role == "security"
    assert art.verdict == "pass"
    assert len(art.payload["threat_model"]) == 2
    assert art.payload["threat_model"][0]["stride_category"] == "Spoofing"
    assert art.payload["threat_model"][0]["severity"] == "high"
    # implementation_rules are consumed VERBATIM by CodingAgent — assert
    # the argon2id directive passes through unchanged.
    assert any(
        "argon2id" in rule for rule in art.payload["implementation_rules"]
    )
    assert art.payload["blocking_concerns"] == []
    assert art.input_tokens == 200
    assert art.output_tokens == 150


@pytest.mark.asyncio
async def test_security_block_on_unsafe_request() -> None:
    agent = SecuritySpecialist()
    agent._publish_event = AsyncMock()  # type: ignore[method-assign]
    agent._claude.chat = _claude_returns(  # type: ignore[method-assign]
        {
            "threat_model": [
                {
                    "stride_category": "Information Disclosure",
                    "threat": "DB breach exposes every user's reusable password",
                    "severity": "critical",
                    "mitigation": "Store argon2id hashes only; never store recoverable passwords.",
                }
            ],
            "implementation_rules": [],
            "compliance_notes": [
                "OWASP ASVS 2.1.1 forbids reversible password storage."
            ],
            "blocking_concerns": [
                "Ticket requires storing passwords in plaintext — unacceptable.",
                "Ticket requires emailing plaintext passwords to users — unacceptable.",
            ],
        },
        verdict="block",
    )

    art = await agent.run_specialist(
        _ctx(
            "Store passwords as plain VARCHAR so we can email them back on "
            "forgot-password."
        )
    )

    assert art.verdict == "block"
    assert len(art.payload["blocking_concerns"]) >= 1
    assert any(
        "plaintext" in concern.lower()
        for concern in art.payload["blocking_concerns"]
    )
    # Rules list is empty on a hard block — the ticket is not fixable
    # with an implementation rule, which is exactly the block criterion.
    assert art.payload["implementation_rules"] == []


def test_security_user_prompt_contains_xml_envelope() -> None:
    """_build_user_prompt must route through _build_initial_message.

    Revision §5 mandates every specialist's user prompt start with the
    base-class XML envelope so user-content escaping isn't bypassed. A
    subclass that inlined json.dumps of the ticket/repo/BA/SA artifacts
    (the pre-revision shape from the plan) would fail this test by
    missing one or more of the envelope tags — which is exactly the
    regression we want to block.
    """
    agent = SecuritySpecialist()
    prompt = agent._build_user_prompt(_ctx())

    # Envelope tags produced by _build_initial_message.
    assert "<task_role>" in prompt
    assert "<jira_ticket>" in prompt
    assert "<repository>" in prompt
    assert "<prior_artifacts>" in prompt

    # Role-specific trailing instruction appended by this specialist.
    assert "<task>" in prompt
    assert "</task>" in prompt
    assert "STRIDE" in prompt
    assert "implementation_rules" in prompt
    # Block guidance is load-bearing — Security is the only live
    # specialist allowed to block, and the trailer MUST remind it that
    # the criterion is "flatly unsafe", not "feels risky".
    assert "flatly unsafe" in prompt

    # Both prior artifacts should render inside <prior_artifacts>.
    assert "business_analyst" in prompt
    assert "stack_analyst" in prompt
