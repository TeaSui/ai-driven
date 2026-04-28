"""End-to-end runner for the reviewer eval suite.

Loads each JSON fixture from ./dataset/, instantiates a `ReviewAgent` against a
`FakeSCM`, captures the agent's structured output, runs both code-based and
model-based graders, and emits a JSON report.

This module is intentionally importable so a future CI workflow or notebook can
call `run_all()` directly. The pytest entry in `test_reviewer_eval.py` is a thin
wrapper.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from tests.evals.reviewer.code_graders import (
    GraderCheck,
    grade_case,
    parse_reviewer_output,
    pass_rate,
)
from tests.evals.reviewer.fake_scm import FakeSCM

logger = logging.getLogger(__name__)

DATASET_DIR = Path(__file__).parent / "dataset"


@dataclass
class CaseReport:
    name: str
    verdict_expected: str
    verdict_actual: str
    output_text: str
    code_checks: list[dict[str, Any]] = field(default_factory=list)
    model_scores: dict[str, int] = field(default_factory=dict)
    model_combined: float = 0.0
    code_pass_rate: float = 0.0
    error: str | None = None

    def as_summary(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "verdict": {"expected": self.verdict_expected, "actual": self.verdict_actual},
            "code_pass_rate": self.code_pass_rate,
            "model_combined": self.model_combined,
            "model_scores": self.model_scores,
            "code_failures": [c["name"] for c in self.code_checks if not c["passed"]],
            "error": self.error,
        }


def load_cases() -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for path in sorted(DATASET_DIR.glob("*.json")):
        with path.open(encoding="utf-8") as fh:
            cases.append(json.load(fh))
    return cases


async def run_one(case: dict[str, Any], *, run_model_grader: bool = True) -> CaseReport:
    """Execute the reviewer agent end-to-end against a single case."""
    from ai_driven.agents.reviewer.agent import ReviewAgent
    from ai_driven.agents.reviewer.config import ReviewConfig
    from ai_driven.evaluation.grader import TaskGrader

    scm = FakeSCM.from_case(case)
    agent = ReviewAgent(scm_client=scm, review_config=ReviewConfig())

    pr = case["pr"]
    task_input = {
        "repo_full_name": pr["repo_full_name"],
        "pr_number": pr["pr_number"],
        "pr_title": pr.get("title", ""),
        "pr_description": pr.get("description", ""),
        "scm_type": pr.get("scm_type", "github"),
        "diff_lines": pr.get("diff_lines", 0),
    }

    expectations = case.get("expectations", {})
    report = CaseReport(
        name=case["name"],
        verdict_expected=expectations.get("verdict", "commented"),
        verdict_actual="",
        output_text="",
    )

    try:
        result = await agent.run(task_input, max_iterations=10)
        report.output_text = getattr(result, "output", "") or ""
    except Exception as exc:  # noqa: BLE001 — eval should report, not crash
        report.error = f"{type(exc).__name__}: {exc}"
        return report

    parsed = parse_reviewer_output(report.output_text) or {}
    raw_verdict = (parsed.get("verdict") or "").lower()
    if raw_verdict in ("approve", "approved"):
        report.verdict_actual = "approved"
    elif raw_verdict in ("request_changes", "changes_requested", "changes"):
        report.verdict_actual = "changes_requested"
    else:
        report.verdict_actual = "commented"

    checks: list[GraderCheck] = grade_case(report.output_text, expectations, case)
    report.code_checks = [asdict(c) for c in checks]
    report.code_pass_rate = pass_rate(checks)

    if run_model_grader:
        try:
            grader = TaskGrader()
            results = await grader.grade_all(
                task_input=json.dumps(task_input),
                agent_output=report.output_text,
                agent_type="review",
            )
            report.model_scores = {r.dimension: r.score for r in results}
            report.model_combined = grader.combined_score(results, agent_type="review")
        except Exception:  # noqa: BLE001
            logger.exception("model grader failed for case %s", case["name"])

    return report


async def run_all(*, run_model_grader: bool = True) -> dict[str, Any]:
    cases = load_cases()
    reports: list[CaseReport] = []
    for case in cases:
        report = await run_one(case, run_model_grader=run_model_grader)
        reports.append(report)

    n = len(reports)
    avg_code = round(sum(r.code_pass_rate for r in reports) / n, 3) if n else 0.0
    avg_model = round(sum(r.model_combined for r in reports) / n, 2) if n else 0.0
    return {
        "n_cases": n,
        "avg_code_pass_rate": avg_code,
        "avg_model_combined": avg_model,
        "cases": [r.as_summary() for r in reports],
        "details": [asdict(r) for r in reports],
    }


def main() -> None:  # pragma: no cover — CLI entry
    """`python -m tests.evals.reviewer.runner [--no-model]` to print a report."""
    import sys

    run_model = "--no-model" not in sys.argv
    out_path = os.getenv("REVIEWER_EVAL_REPORT")

    report = asyncio.run(run_all(run_model_grader=run_model))
    summary = {
        k: v for k, v in report.items() if k != "details"
    }
    print(json.dumps(summary, indent=2))
    if out_path:
        Path(out_path).write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"\nFull report written to {out_path}")


if __name__ == "__main__":  # pragma: no cover
    main()
