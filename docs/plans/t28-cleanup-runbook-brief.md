# T28 — Cleanup + Phase 2 runbook (brief, v1)

**Goal.** Phase 2 wrap-up: docs, dead-code removal, operator runbook for the day-2 team.

**Depends on:** Everything (T20-T27).

## Scope

### 1. Runbook — `docs/runbooks/orchestrator-phase2.md`

Structure:
- **Overview** — what the orchestrator does, 3 tiers, where specialists live.
- **State machine** — task states and transitions (queued → running → orchestrating → awaiting_qa → completed / qa_failed / blocked / failed). Mermaid diagram.
- **Env flags** — `AI_DRIVEN_ORCHESTRATOR_MODE` (shadow|live|off), `AI_DRIVEN_ORCHESTRATOR_TREAT_ERRORS_AS_PASS` (bool).
- **Common tasks:**
  - "Task stuck at awaiting_qa" → check `qa_enqueued_at`, reap via admin override.
  - "Specialist error rate spiking" → check counters, consider flipping to shadow.
  - "qa_failed task is false positive" → admin `rerun_qa` or `force_pass`.
  - "Orchestrator queue DLQ has messages" → investigate message body, fix the root cause, redrive.
  - "Need to rollback to shadow" → env flip + audit stuck tasks (T26 procedure).
- **Dashboards** — the counters that matter, linked.
- **Alerting** — the P2/P3 alerts T22/T23/T24 emit; what each means, what to do.
- **Known false positives** — a growing list populated from dogfood (T27) + production runs.

### 2. Dead code removal

Grep + delete:
- `_should_halt_pipeline`'s `halted_on == "qa"` exclusion (T21) — was added because QA ran pre-coding; post-T22 QA is post-coding, exclusion is dead. Remove + update test.
- Any v1 `QaPayload` usage outside `artifacts.py` — v2 is the only shape written post-T22.
- `scripts/phase2_shadow_baseline.py` — used for T0/T0-rerun. Keep but rename to `scripts/phase2_shadow_baseline_archive.py` + add a deprecation header pointing at `phase2_dogfood_report.py`.

### 3. Phase 2 retrospective — `docs/phase2-retrospective.md`

Short doc:
- What landed (T18-T27).
- What we learned (T-A1, T0-rerun outcomes).
- What we cut (narrow TechLead, stack guides).
- What's deferred to Phase 3 (TechLead v2 with mobile+AWS consumers; stack guides library; more specialist variants).

### 4. Update Phase 2 plan status

`docs/plans/2026-05-01-orchestrator-phase-2.md` — mark every task completed with its PR link. Add a "status: SHIPPED" header at the top.

### 5. Changelog entry

`CHANGELOG.md` (create if not present) — add a "Phase 2" section with one-line-per-feature summaries.

### 6. README updates

If the project has a README with orchestrator references, update to reflect Phase 2 final state.

## Commit sequence

1. `docs(runbook): orchestrator Phase 2 operator guide`
2. `chore(cleanup): remove dead halted_on=='qa' exclusion + update test`
3. `chore(scripts): rename phase2_shadow_baseline -> _archive`
4. `docs(retrospective): Phase 2 wrap-up`
5. `docs(plans): mark Phase 2 plan shipped`
6. `docs(changelog): Phase 2 entries`

## Risks

1. **Runbook decay.** A runbook that's not updated is worse than no runbook. Add a note at the top: "Last verified: <date>; update with any new state or alerting change."
2. **Dead-code removal triggers test failures.** Run full suite after each removal commit.
