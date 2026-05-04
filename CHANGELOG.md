# Changelog

All notable changes to ai-driven are recorded here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); entries are
grouped by shipping phase rather than semver since the project is
pre-1.0.

## [Unreleased]

### Removed
- **Legacy `WorkflowAgent` path.** The pre-orchestrator dispatch agent
  (`agents/workflow/{agent,state_machine,triggers}.py`, its prompt, and
  ~1,880 LOC of tests) was never routed through the SQS consumer
  factory and was only reachable via the debug/inline chat path. Its
  responsibilities are fully owned by the orchestrator wrapper + the
  post-coding QA wrapper. Surface removal shipped in PR 1
  (frontend `AgentType` union, NewRunModal tile, onboarding tile,
  dashboard card, `chat/router.py` inline agent_map); full deletion
  shipped in PR 2 (source tree, tests, mock provider branches, tool
  registry fallback, admin `_AGENT_COMPLETED_STAGE['workflow']` entry,
  `ActiveAgentsResponse.workflow` field). Alembic migration
  `b3c4d5e6f7a8` rewrites historical `tasks.agent_type='workflow'` rows
  to `'coding'` so the admin timeline projection stays truthful; the
  rewrite is lossy for analytics and downgrade is a no-op.

## [Phase 2] - 2026-05-01

Orchestrator moves from Phase 1 telemetry-only to active gating: it can
halt pipelines on specialist blocks, validate PR diffs post-coding via a
QA specialist, and expose a human override + stuck-task reaper path. PR
[#33](https://github.com/) on `feature/orchestrator-phase1`.

### Added
- **Orchestrator SQS worker (T20)** — orchestrator runs on its own
  `ai-driven-orchestrator-tasks` queue with crash-safe idempotency
  (`orchestrator_completed_at` marker), DLQ redrive, and
  `orchestrator_mode` stamped per message so mid-deploy mode flips are
  safe.
- **Strict specialist gating + `blocked` status (T21)** — live-mode
  pipeline halts when any specialist returns `verdict=block`;
  `ck_tasks_status` grows a `blocked` value; blocker details captured
  to `Task.output_data` (role, reasons, artifact id).
- **QA post-coding specialist (T22)** — new `ai-driven-qa-tasks` queue
  and `_QaPostCodingWrapper` consume coding-worker output, fetch the
  PR diff, validate BA acceptance_criteria + Security
  implementation_rules, emit QA payload v2 with per-rule/per-AC
  evidence.
- **QA verdict → Task status (T23)** — `awaiting_qa` and `qa_failed`
  statuses added to `ck_tasks_status`; pure `decide_qa_gate` function
  maps verdict → status transition; wrapper applies the decision
  atomically with failure details on `output_data.qa_failures`;
  replay-recovery sweeps stranded `awaiting_qa` tasks.
- **Admin override + stuck-task reapers (T24)** —
  `POST /api/admin/tasks/{id}/override-block` (admin-only) re-enqueues
  coding (for `blocked`) or re-runs QA / force-passes (for
  `qa_failed`); stuck `orchestrating`/`awaiting_qa`/`running` tasks are
  reaped after 30 min via a background loop; every action writes a
  `task_override_audit` row and emits
  `orchestrator.block_override{role,action}` +
  `reaper.{role}.reaped` counters.
- **Specialists UI tab + override button (T25)** — task-detail page
  gains a "Specialists" tab with per-role ArtifactCard + QAFailureCard
  components; admins see an override button on blocked/qa_failed tasks
  wired into the T24 endpoint.
- **Dogfood harness + results report (T27)** — `phase2_dogfood_corpus.yaml`
  seeds the measurement set; `phase2_dogfood_harness.py` submits +
  polls with 429 backoff; `phase2_dogfood_report.py` converts JSONL →
  markdown summary; `GET /api/admin/override-audit` feeds FP-rate
  numbers into the report.
- **Phase 2 runbook (T28)** — new `docs/runbooks/06-orchestrator.md`
  covering state machine (mermaid), env flags, common ops tasks,
  DataDog counters, alert response, and a seeded "Known false
  positives" section.

### Changed
- CodingAgent reads `prior_artifacts` from `task_artifacts` and threads
  them through `prompt_composer` (T18/T19) so BA acceptance_criteria +
  Security implementation_rules land verbatim in its system prompt.
- CodingAgent persists `pr_number` + `pr_url` on PR creation with
  idempotent retry on SQS redelivery, including 422
  branch-already-has-PR recovery (T19b).
- QA removed from pre-coding `final_gate` in `routing.py`; invoked
  post-coding with the PR diff via its own wrapper (T22).
- Cost attribution threaded end-to-end from specialist → artifact row
  → cost-ceiling check (T-A2).
- Specialist `max_output_tokens` raised to 16K and the
  `orchestrator.specialist.fallback_activated` counter added so
  error-rate regressions are observable (T-A1).

### Deprecated
- `scripts/phase2_shadow_baseline.py` renamed to
  `scripts/phase2_shadow_baseline_archive.py` with an ARCHIVED docstring
  pointing at `phase2_dogfood_report.py` as the replacement. Kept for
  T0/T0-rerun reproducibility; do not modify.

### Removed
- `halted_on == 'qa'` carve-out in
  `queue/consumer.py::_should_halt_pipeline` — post-T22 QA runs
  post-coding so the exclusion is unreachable; strict gating is now
  role-agnostic.
- In-process shadow-orchestrator fire-and-forget path in the HTTP
  layer — all orchestration goes through SQS now.

### Ops state
- **Live mode active** — `AI_DRIVEN_ORCHESTRATOR_MODE=live` pinned in
  `.env.local`. Rollback procedure in
  `docs/runbooks/06-orchestrator.md` → "Rollback to shadow mode."
- **Seed team renamed** — the reaper-attribution seed team is named
  `admin` (not `system`), email `admin@ai-driven.local`, seeded with a
  non-functional `__seed__` hash. Rotate to a real key before use.
- **Consumer terminal-state preservation** — `_process_message` respects
  wrapper-committed `blocked` / `qa_failed` and does not overwrite them
  with `completed`.
- **Timeline stage mapping** — `blocked` / `awaiting_qa` / `qa_failed`
  have explicit stage + stageStatus projections so the Timeline UI
  renders gated tasks accurately (see runbook).

### Docs
- `docs/retrospectives/2026-05-01-phase-2.md` — what landed / learned / cut /
  deferred.
- `docs/archive/phase-2/plan.md` — annotated with
  commit SHAs and SHIPPED header.
- `docs/runbooks/06-orchestrator.md` — operator runbook (see
  Added, above).

### Tests
Suite grows from 960 → 994 passed with 41 pre-existing Bedrock /
mocked-Claude failures unchanged. New coverage across T22–T27 wires
halt predicates, QA gate decisions, override-endpoint permissions, and
dogfood-report parsing.
