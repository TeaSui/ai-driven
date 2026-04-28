"""Live end-to-end reviewer eval. Opt-in via `pytest -m eval` (see conftest).

Each case calls the real Anthropic API. Don't run on every commit —
schedule it nightly or before promoting prompt changes.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from tests.evals.reviewer.runner import run_all

pytestmark = pytest.mark.eval


# Quality gates. Bump these as the agent improves; never lower without sign-off.
MIN_AVG_CODE_PASS_RATE = 0.85
MIN_AVG_MODEL_COMBINED = 7.0
MIN_PER_CASE_CODE_PASS_RATE = 0.6


async def test_reviewer_eval_suite() -> None:
    report = await run_all(run_model_grader=True)

    out_path = os.getenv("REVIEWER_EVAL_REPORT")
    if out_path:
        Path(out_path).write_text(json.dumps(report, indent=2), encoding="utf-8")

    summary_for_print = {k: v for k, v in report.items() if k != "details"}
    print("\n=== Reviewer eval ===\n" + json.dumps(summary_for_print, indent=2))

    failures: list[str] = []
    for case in report["cases"]:
        if case["error"]:
            failures.append(f"{case['name']}: agent crashed — {case['error']}")
            continue
        if case["code_pass_rate"] < MIN_PER_CASE_CODE_PASS_RATE:
            failures.append(
                f"{case['name']}: code_pass_rate={case['code_pass_rate']} "
                f"< {MIN_PER_CASE_CODE_PASS_RATE}; failures={case['code_failures']}"
            )
    if failures:
        pytest.fail("\n".join(failures))

    assert report["avg_code_pass_rate"] >= MIN_AVG_CODE_PASS_RATE, (
        f"Avg code-grader pass rate {report['avg_code_pass_rate']} "
        f"< floor {MIN_AVG_CODE_PASS_RATE}"
    )
    assert report["avg_model_combined"] >= MIN_AVG_MODEL_COMBINED, (
        f"Avg model-grader score {report['avg_model_combined']} "
        f"< floor {MIN_AVG_MODEL_COMBINED}"
    )
