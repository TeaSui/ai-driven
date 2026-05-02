"""Unit tests for SpecialistContext.pr_ref (T22).

pr_ref is optional and defaults to None so every pre-coding specialist
call site keeps working unchanged. The QA specialist prompt switches to
post-coding mode when pr_ref is populated — verified by asserting the
rendered user prompt contains the <pr_ref> envelope tag.
"""
from __future__ import annotations

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.agents.specialists.base import SpecialistContext
from ai_driven.agents.specialists.qa.agent import QaSpecialist


def _base_ctx(**overrides) -> SpecialistContext:
    defaults = dict(
        task_id="t1",
        ticket={"summary": "s", "description": "d",
                "acceptance_criteria": "", "labels": []},
        repo={"full_name": "org/repo", "scm_type": "github",
              "default_branch": "main"},
        tier="full_workflow",
        prior_artifacts={
            "business_analyst": Artifact(
                role="business_analyst", verdict="pass", reasoning="",
                payload={"ambiguities_resolved": [],
                         "acceptance_criteria": [],
                         "out_of_scope": [], "blocking_questions": []},
                model_used="m", input_tokens=0, output_tokens=0,
            ),
        },
    )
    defaults.update(overrides)
    return SpecialistContext(**defaults)


def test_specialist_context_pr_ref_defaults_to_none() -> None:
    ctx = _base_ctx()
    assert ctx.pr_ref is None


def test_specialist_context_pr_ref_accepts_dict() -> None:
    ctx = _base_ctx(pr_ref={"pr_number": 42, "pr_url": "https://x/pr/42"})
    assert ctx.pr_ref == {"pr_number": 42, "pr_url": "https://x/pr/42"}


def test_qa_user_prompt_is_pre_coding_when_pr_ref_is_none() -> None:
    """Back-compat: no pr_ref → legacy pre-coding trailer, no <pr_ref> tag."""
    agent = QaSpecialist()
    ctx = _base_ctx()
    prompt = agent._build_user_prompt(ctx)
    assert "<pr_ref>" not in prompt
    assert "POST-CODING mode" not in prompt
    # Legacy trailer must still render so unit tests of the old shape
    # keep their anchor.
    assert "Run GO/NO-GO gate checks" in prompt


def test_qa_user_prompt_switches_to_post_coding_when_pr_ref_set() -> None:
    agent = QaSpecialist()
    ctx = _base_ctx(pr_ref={"pr_number": 123, "pr_url": "https://x/pr/123"})
    prompt = agent._build_user_prompt(ctx)
    assert "<pr_ref>" in prompt
    assert "<pr_number>123</pr_number>" in prompt
    assert "POST-CODING mode" in prompt
    assert "github.get_pr_diff(repo_full_name, 123)" in prompt
    assert "schema_version=\"v2\"" in prompt


def test_qa_user_prompt_post_coding_requires_pr_number() -> None:
    """Empty/0 pr_number should NOT flip to post-coding mode."""
    agent = QaSpecialist()
    ctx = _base_ctx(pr_ref={"pr_number": 0, "pr_url": ""})
    prompt = agent._build_user_prompt(ctx)
    # Falsy pr_number → defensive fallback to pre-coding trailer.
    assert "<pr_ref>" not in prompt
