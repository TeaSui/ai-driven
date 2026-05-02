# Eval Suite

Regression tests for AI agents. Built per the curriculum at
[docs/building-with-the-claude-api/](../../docs/building-with-the-claude-api/),
section 2 — *Structured Output & Evaluation* (lessons 9–14).

Two layers:

1. **Code-based graders** — deterministic pytest unit tests. Run on every CI
   build. No API cost.
2. **End-to-end model evals** — opt-in. Run the real agent against a fixture
   PR, grade with the existing model-based `TaskGrader`, fail the build if
   quality drops below the per-suite floor.

## Suites

| Agent | Folder | Notes |
|---|---|---|
| Reviewer | [reviewer/](reviewer/) | 3 fixture cases: SQL injection, clean PR, N+1 query |

## Running

Default `pytest` skips the live evals — they cost real Anthropic tokens.

```bash
# Cheap unit tests of graders only — runs by default
pytest tests/evals -k graders

# Live, end-to-end. Burns API budget. Schedule nightly or pre-prompt-change.
pytest -m eval tests/evals
# …or:
RUN_EVALS=1 pytest tests/evals

# Save the full report (every check + every model score per case)
REVIEWER_EVAL_REPORT=/tmp/reviewer_eval.json pytest -m eval tests/evals/reviewer

# Run the live runner directly (no pytest, no quality gate):
python -m tests.evals.reviewer.runner
python -m tests.evals.reviewer.runner --no-model   # skip the Haiku grader
```

## Adding a reviewer case

Drop a JSON file into [reviewer/dataset/](reviewer/dataset/) following the
existing schema. Required keys:

```jsonc
{
  "name": "case_NNN_short_label",
  "description": "What the reviewer must catch (or correctly approve).",
  "pr": {
    "repo_full_name": "...",
    "pr_number": 0,
    "scm_type": "github",
    "title": "...",
    "description": "...",
    "diff_lines": 0
  },
  "diff": {
    "total_additions": 0,
    "total_deletions": 0,
    "files": [
      {"path": "...", "status": "modified", "additions": 0, "deletions": 0, "patch": "..."}
    ]
  },
  "extra_files": {"path": "full file content for repo.read_file"},
  "expectations": {
    "verdict": "approved | changes_requested | commented",
    "must_flag_files": ["..."],
    "must_not_flag_files": ["..."],
    "must_mention_any": ["sql injection", "parameterized"],
    "min_critical_findings": 0,
    "min_total_findings": 0,
    "max_total_findings": 99
  }
}
```

Bias the dataset towards the failure modes that actually hurt — security
misses, false positives on cosmetic PRs, and severity miscalibration. A clean
"this PR is fine" case is as valuable as a bug-laden one for measuring
false-positive rate.

## Quality floors

In [reviewer/test_reviewer_eval.py](reviewer/test_reviewer_eval.py):

| Floor | Value | Meaning |
|---|---|---|
| `MIN_AVG_CODE_PASS_RATE` | 0.85 | average of per-case `passed/total` checks |
| `MIN_AVG_MODEL_COMBINED` | 7.0  | average of `TaskGrader.combined_score` (1–10) |
| `MIN_PER_CASE_CODE_PASS_RATE` | 0.6 | individual case can't fall below this |

Raise these as the agent improves. Lowering one requires a written reason in
the commit message — the floors are a ratchet, not a slider.
