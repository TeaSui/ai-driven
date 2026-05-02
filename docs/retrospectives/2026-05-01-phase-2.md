# Phase 2 Orchestrator — Retrospective

**Date:** 2026-05-01 (close of Phase 2 on `feature/orchestrator-phase1`).
**PR:** #33 (Phase 2 bundled merge).
**Plan:** `docs/plans/2026-05-01-orchestrator-phase-2.md`.

---

## What landed

Phase 2 turned the orchestrator from a telemetry-only observer (Phase 1)
into an active gate that can halt pipelines, persist structured blocker
context, run post-coding validation, and be overridden by humans.

Functional scope delivered by T20–T28 (commit SHAs from PR #33):

- **T-A2 — Cost attribution end-to-end + cost-ceiling compounding fix**
  (`1a6c1e6`). Fixed the silent $0.00 attribution bug that corrupted every
  T0 baseline number.
- **T-A1 — Specialist error-rate audit + ≤15% fallback spec**
  (`6b1b7bb` raising max_output_tokens to 16K, `45158ef` fallback-on-error
  flag + metric, `8ed5898` Bedrock payload shape fix). Documented in
  `docs/phase2-error-audit.md`.
- **T18 — `prompt_composer` pure module** (`64723d7`).
- **T19 — CodingAgent reads prior_artifacts from `task_artifacts`**
  (`b6ccdef`).
- **T19b — CodingAgent persists `pr_number` + crash-safe idempotency**
  (`ab3c8d8`, `589d8fe`, `67d6b72`).
- **T20 — Orchestrator as SQS worker** (`af51db1`, `c58f9ce`,
  `1615c1e`, `8ba6821`, `7757425`).
- **T21 — Live-mode strict gating + `blocked` status migration**
  (`7e8cfce` migration, `c434f9d` halt predicate).
- **T22 — QA post-coding specialist** (`9a6c8e3` payload v2, `3ff86e8`
  routing drop of pre-coding QA, `86d764a` pr_ref in context, `b6f6988`
  QA SQS queue + agent factory, `280b421` _QaPostCodingWrapper,
  `dacb575` coding worker enqueues qa-task, `e8c091e` test coverage).
- **T23 — QA verdict → Task status** (`5ea27ff` `awaiting_qa` migration,
  `0e5ac71` `qa_failed` migration, `cbfd450` `decide_qa_gate` pure fn,
  `b9a5458` wrapper applies decision atomically, `c66aa3e`
  replay-recovery, `0846cc4` admin status-bucket constants, `eb2195d`
  integration coverage).
- **T24 — Admin override endpoint + stuck-task reapers** (`96d8c43`
  migration + seeds, `2663353` require_admin dependency, `2a02c3c`
  heartbeat writes, `3f3c9c0` override endpoint, `2fef2a8` reaper
  module, `92ebb05` reaper loop at startup, `3fb803c` test coverage,
  `65a3459` overrides helper extract, `0bf0def` available_actions).
- **T25 — Specialists UI tab + override button** (`1ab836f`
  `list_rows_for_task` DB helper, `b4fa32c` `GET
  /tasks/{id}/artifacts`, `3a6ae7d` UI API helpers, `1d2ca7f`
  ArtifactCard + QAFailureCard, `c41329d` tab wiring, `1acbfd7` test
  coverage).
- **T26 — Cutover flip (pending)**. The orchestrator-mode env flip is
  a manual operational step intentionally held outside PR #33;
  committed no code beyond defaults. Tracked on the Phase 2 plan; the
  ops runbook step is in `docs/runbooks/orchestrator-phase2.md`.
- **T27 — Dogfood harness + results report** (`d9e39e3` override-audit
  endpoint, `085f173` corpus yaml, `1196e02` harness, `4ba3625` report,
  `ffcddbc` tests).
- **T28 — Phase 2 cleanup + runbook** (this document, the runbook at
  `docs/runbooks/orchestrator-phase2.md`, dead-code removal of the
  `halted_on == 'qa'` exclusion, and the archival of
  `scripts/phase2_shadow_baseline.py`).

Tests end Phase 2 at 994 passed / 41 pre-existing Bedrock failures —
the Phase-2 baseline of 960 passed grew by ~34 new tests across T22–T27
without new regressions.

---

## What we learned

**T0 shadow baseline was not useful as an abort gate.** The initial T0
run (`docs/phase2-shadow-baseline.md`) reported 45–73% specialist error
rates and 75% QA block rate on n=4. Those numbers were mostly infra
noise: credit exhaustion, Bedrock prefill validation, and max_tokens
truncation. The "abort-strict" recommendation was provisional — the
sample was too small and too contaminated by infra flake to trust.

**Red-teaming v5 → v6 of the plan was load-bearing.** Red-team round 5
rejected a pure "pause + grow sample" approach and forced the Path A+B
hybrid (lenient gating ships first; strict ships only if T0-rerun
passes). Round 6 corrected the T18-vs-T19 ordering — building T19
first would have produced a stub composer with zero topology signal.
Both corrections saved real calendar time.

**Crash-safety is expensive to retrofit.** T19b's PR-create
idempotency (branch-already-has-PR detection, `pr_number` persist,
422-parse-on-retry) was bigger than planned. T20's
`orchestrator_completed_at` idempotency marker and the full SQS-worker
architecture flowed from the same realisation: synchronous orchestrate
in an HTTP path is untenable the moment you have >1 specialist in the
chain.

**Dead code accumulates fast.** Between T21 and T28, we shipped a
`halted_on == 'qa'` carve-out (T21), moved QA to post-coding (T22),
and rendered the carve-out unreachable — all in 96 hours. The
exclusion would have sat as dead code indefinitely if the wrap-up
task had not explicitly called it out. Keeping a cleanup task on
every plan is not optional.

**Real dogfood data beats red-team speculation.** T27 ran 10 real
tickets end-to-end against the live pipeline. The outcomes exposed
specific FP patterns and confirmed that the "verbatim
Security.implementation_rules in diff" criterion was measurable —
things no amount of pre-shipping red-team could have produced.

---

## What we cut

**TechLead specialist stayed a stub.** Red-team round 4 removed it from
Phase 2 because no downstream consumer (mobile/AWS specialists) exists
yet to read its output. Ships in Phase 3 alongside those consumers.

**Stack guides library.** The prompt-composer in T18 can inject
language-specific guides, but the library of guides was descoped —
every Phase 2 ticket got BA acceptance_criteria + Security
implementation_rules only.

**Per-team `orchestrator_mode` column.** User-locked decision: global
flip is enough for Phase 2. Per-team rollout waits for Phase 3 if
demand materialises.

**Multi-repo CodingAgent support.** Still pinned to one
`repo_id`-per-team.

**Actual test execution during QA.** QA reads the PR diff and
validates *static* evidence that ACs/rules are present. Running the
test suite during QA (so a missing test that breaks red-green-refactor
gets caught) is Phase 2.1 — contingent on CI integration.

**UX Designer + API Test specialists stayed stubs.** Same reasoning
as TechLead — no consumer in Phase 2 pipeline.

---

## What's deferred to Phase 3

1. **TechLead v2 with mobile + AWS consumers.** Full
   contract/ADR-producing TechLead, read by new mobile and AWS
   specialists that can enforce mobile-build and IaC-compliance rules
   on the PR diff.
2. **Stack guides library.** Per-language playbooks (Python/Go/TS/
   Kotlin/Swift) injected into CodingAgent's prompt when a specialist
   output names that stack.
3. **More specialist variants.** API-test, UX-designer, and a
   perf-analysis specialist — each gated by evidence of utility, not
   by "we wrote the interface already."
4. **CI integration for QA test-runs.** Convert QA from static-
   evidence validation to dynamic validation (run the test suite,
   parse results, block on failures).
5. **Per-team orchestrator mode + progressive rollout tooling.**
   Customer A flips to live, customer B stays shadow — today's global
   flip can't do this.
6. **Reaper → auto-retry where safe.** Today's reapers mark stuck
   tasks failed. A subset of stuck cases (transient SQS visibility,
   Bedrock 5xx) could auto-retry once before reaping.

---

## Ops state at Phase 2 close

- **Live mode active** — `AI_DRIVEN_ORCHESTRATOR_MODE=live` pinned in
  `.env.local` (flipped 2026-05-01). Cutover smoke record:
  `docs/phase2-cutover-smoke-results.md`.
- **Seed team named `admin`** with `admin@ai-driven.local` member
  (migration `f6a7b8c9d0e1`). Ships with a non-functional `__seed__`
  hash; operators rotate before first use.
- **Consumer terminal-state preservation** in place — wrapper-committed
  `blocked` / `qa_failed` are not overwritten with `completed` on
  successful `AgentResult`.
- **Timeline stage projections** cover all Phase 2 statuses (see
  runbook "Timeline stage mapping").

## Operational next steps (owned by ops, not this PR)

- Wire the P2/P3 alerts in the runbook into the on-call rotation.
- Review override-audit weekly for the first month post-cutover to
  spot FP patterns early; feed findings into the runbook's "Known
  false positives" section.
