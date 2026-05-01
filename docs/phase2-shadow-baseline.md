# T0: Phase 2 Shadow Baseline

**Date:** 2026-05-01
**Branch:** `feature/orchestrator-phase1`
**Source:** `task_artifacts` + `tasks` on the local Postgres dev DB.
**Generator:** `scripts/phase2_shadow_baseline_archive.py` (read-only; archived in T28 — see module docstring).

## TL;DR

**Recommendation: ABORT-STRICT (provisional — sample size too small to trust).**

Every role's *effective* block rate is well below the 40% abort threshold, but
every role also has an **error rate ≥ 45%** driven by upstream LLM
infrastructure failures (Bedrock `max_tokens`, AWS credit exhaustion). Flipping
to strict gating right now would halt a majority of pipelines because of
infrastructure flakiness, not specialist judgment — and we'd have no signal to
distinguish the two.

Also: **49 artifacts across 11-13 per role is too few (target ≥ 10 per role
*with a non-error verdict*) to draw meaningful conclusions**. The report below
applies the exit-criteria math mechanically, but the primary finding is
"gather more data before flipping the switch."

## 1. Sample size

| Metric | Value |
|---|---|
| Total artifacts | 49 |
| Distinct roles observed | `business_analyst`, `stack_analyst`, `security`, `qa`, `tech_lead` |
| Task status distribution | completed=34, failed=16, cancelled=2 |

`tech_lead` has 3 artifacts, all `not_implemented` — the specialist isn't
wired up yet. Excluded from the gating analysis below.

## 2. Per-role verdict distribution

| Role | Total | pass | pass_with_notes | block | error | not_implemented | ~cost/run (USD) |
|---|---:|---:|---:|---:|---:|---:|---:|
| business_analyst | 13 | 0 (0%) | 5 (38.5%) | 2 (15.4%) | 6 (46.2%) | 0 (0%) | 0.00 |
| stack_analyst    | 11 | 0 (0%) | 5 (45.5%) | 0 (0%)   | 6 (54.5%) | 0 (0%) | 0.00 |
| security         | 11 | 0 (0%) | 3 (27.3%) | 0 (0%)   | 8 (72.7%) | 0 (0%) | 0.00 |
| qa               | 11 | 0 (0%) | 1 (9.1%)  | 3 (27.3%)| 7 (63.6%) | 0 (0%) | 0.00 |
| tech_lead        | 3  | 0 (0%) | 0 (0%)    | 0 (0%)   | 0 (0%)    | 3 (100%) | 0.00 |

Notes:

- **Every `cost_usd` is 0.00.** Either the cost-attribution hook isn't wired
  (it was added in the orchestrator-revision spec, §2.2) or every recorded
  run pre-dates it. Cost per run is unknowable from this dataset.
- **`pass` verdict never occurred**, across any role. Every non-error judgment
  is either `pass_with_notes` or `block`. This is a useful finding on its own:
  if we gate on "verdict != pass" we halt 100% of pipelines, so the halt
  condition has to be `verdict == block` (which is what Phase 2 proposes).

## 3. Block-rate analysis

Block rate is computed two ways:

- **Raw** = blocks / total artifacts (includes errors in denominator)
- **Effective** = blocks / (total − errors − not_implemented) — the rate
  among runs where the specialist actually produced a judgment

| Role | Blocks | Non-error runs | Raw block rate | Effective block rate |
|---|---:|---:|---:|---:|
| business_analyst | 2 | 7  | 15.4% | 28.6% |
| stack_analyst    | 0 | 5  | 0.0%  | 0.0%  |
| security         | 0 | 3  | 0.0%  | 0.0%  |
| qa               | 3 | 4  | 27.3% | **75.0%** |

QA's *effective* block rate of 75% is alarming on paper — but with only 4
non-error QA runs, it's 3-of-4, not a statistically meaningful signal.

### False-positive breakdown

For each blocking specialist, cross-referenced with the downstream Task
status:

| Role | Total blocks | FP (task completed anyway) | Uncertain (task failed/cancelled) | FP rate |
|---|---:|---:|---:|---:|
| business_analyst | 2 | 0 | 2 | 0.0% |
| qa               | 3 | 1 | 2 | **33.3%** |

The single QA "FP" (`task_id=c731e0be…`, KAN-18) was a block with reasoning
"no PR diff, no test results, and no source code available for any of the 9
acceptance criteria" — i.e. a shadow-mode block where the specialist refused
to evaluate without a PR, but CodingAgent went on to produce one. This is
arguably a **correct** block that only looks like an FP because we measured
completion at task level rather than "would the block have prevented the
successful PR." Flagging this as a measurement-methodology concern rather
than a specialist mistake.

The 4 "uncertain" blocks all landed on tasks that `failed` for unrelated
reasons (likely the same upstream LLM flakiness that produced the 27 error
artifacts). We can't say whether the block was correct — the task never got
far enough to test.

## 4. Error-rate sanity check

| Role | Errors | Error rate |
|---|---:|---:|
| business_analyst | 6 | 46.2% |
| stack_analyst    | 6 | 54.5% |
| security         | 8 | 72.7% |
| qa               | 7 | 63.6% |

**Over half of all specialist runs errored out.** A spot check of a few
reasoning fields shows max_tokens truncation and Bedrock ValidationException
patterns. This is not a specialist-judgment problem; it's an infrastructure
problem. But it has a direct bearing on Phase 2 gating design: if we halt on
`verdict == error` we halt on infra flakes, so **Phase 2 must treat `error`
as a pass-through with retry, not a halt signal**, and needs to surface
error rate as a separate health metric.

## 5. Exit-criteria evaluation

From the Phase 2 plan:

> if any role's block rate is >40% OR its FP rate is >25%, escalate — strict
> gating becomes a bad idea and we should fall back to "pass_with_notes
> only" as the halt trigger.

Applied mechanically:

| Role | Effective block rate | FP rate | Threshold check |
|---|---:|---:|---|
| business_analyst | 28.6% | 0.0% | PROCEED-LENIENT (20% ≤ 28.6% ≤ 40%) |
| stack_analyst    | 0.0%  | n/a  | PROCEED-STRICT |
| security         | 0.0%  | n/a  | PROCEED-STRICT |
| qa               | 75.0% | 33.3%| **ABORT-STRICT** (both thresholds breached) |

Because **any** role triggers the strictest recommendation and QA triggers
ABORT on both axes, the overall recommendation is **ABORT-STRICT**.

## 6. Recommendation

**ABORT-STRICT** — do not flip the switch to block-on-`verdict=block`. Do
one of the following instead:

1. **Preferred:** Fix the 45-73% specialist error rate first (Bedrock
   max_tokens + AWS credit issues), then re-run T0 with a larger sample
   (target: ≥ 20 non-error runs per role). The current dataset is too
   degraded to draw conclusions.
2. **If we must ship gating this sprint:** Gate on `verdict == block` AND
   `blocking_concerns` / `blocking_questions` is non-empty — i.e. require the
   specialist to have cited a concrete reason, not just returned a
   structured-output block. Combined with treating `error` as pass-through
   and `pass_with_notes` as pass, this neutralizes the QA 75% effective block
   rate, which was dominated by "no PR to evaluate" shadow-mode artifacts.
3. **Do not** adopt "pass_with_notes as halt" (the plan's other fallback):
   `pass_with_notes` is 38-45% of business_analyst/stack_analyst non-error
   runs, so that rule would halt nearly all pipelines.

## 7. Caveats / honest uncertainty

- **Sample size is the dominant problem.** 3 QA blocks and 2 BA blocks
  cannot support a 25% FP-rate threshold — one run moves the rate by 33-50
  percentage points.
- **`cost_usd = 0` everywhere** means the cost dimension of this baseline is
  missing. Need to confirm the orchestrator is actually writing cost to
  artifacts (spec §2.2); if not, that's a separate blocker for the Phase 2
  cost-attribution dashboard.
- **"Task completed" is a weak proxy for "block was wrong."** A task can
  complete with a broken PR. A rigorous FP definition would read
  `reviews.status` or the eventual merge state, not Task.status. Flagging
  for T1/T2 to refine.
- **The shadow dataset is skewed toward KAN-18/KAN-19 repeat runs** on a
  payment ticket whose spec is known-ambiguous. Repeat runs of the same
  flawed ticket inflate block counts artificially. Spread across ≥ 10
  distinct tickets before relying on the FP math.

## Appendix A: reproduction

```
.venv/bin/python scripts/phase2_shadow_baseline_archive.py
```

Reads `task_artifacts` + `tasks` via `async_session_factory`. Read-only,
safe to re-run. Output is JSON; the tables above were generated by hand
from the JSON dump.
