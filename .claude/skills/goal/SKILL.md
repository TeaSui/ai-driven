---
name: goal
description: >
  Autonomous end-to-end execution skill. Use this whenever the user sets a high-level goal and
  wants Claude to work independently until done — no hand-holding, no asking for clarification
  mid-task. Triggers on: /goal, "just build it", "do it autonomously", "work on this until done",
  "don't stop until finished", "handle it end-to-end", "ship it", or any request phrased as a
  complete outcome rather than a step-by-step. This skill locks in context + success criteria up
  front and then runs the RECON → PLAN → BUILD → TEST → DEBUG → REVIEW → DELIVER loop without
  interruption. Invoke this skill even if the user only gives a one-line description — you'll
  gather the rest.
---

# /goal — Autonomous Execution

Turn a one-line goal into a shipped result. Infer context, confirm success criteria once, then
execute with per-task verification and a self-review gate — no mid-task check-ins unless truly
blocked.

This skill is written so that ANY model running it produces the same disciplined behavior. Do
not rely on judgment where the skill gives you a procedure — follow the procedure.

---

## Phase 0 — Recon (before asking anything)

Weaker execution starts with wasted questions. Answer as much of the intake yourself as the
repo allows, BEFORE asking the user anything:

1. Read `CLAUDE.md` (project + user), `README.md`, and the package manifest
   (`package.json` / `pyproject.toml` / `go.mod` / `Makefile`) — this usually answers stack,
   conventions, and how to run tests.
2. Run `git log --oneline -10` and `git status` — this answers current state and in-flight work.
3. List the top two directory levels — this answers structure and where new code belongs.

### Routing gate (mandatory — autonomy never bypasses tiers)

Run the trust-boundary checklist from `rules/workflow-routing.md` against the goal, count the
domains, and pick the row that matches. Do NOT "fold security in as a task" — the tier decides
who drives:

| Trust boundary? | Domains | What /goal does |
|---|---|---|
| NO | 1 | Run this skill's Phases 1-5 directly. |
| NO | 2+ | Phases 1-5, with a lightweight plan (`writing-plans`) and parallel leaf dispatches per the coordinated fast path. |
| YES | 1 | Phases 1-5 WRAP the full workflow: the Phase 2 plan's first tasks are Security subagent (and TechLead contract-mode if there's new contract surface); implementation runs via `subagent-driven-development`; API Test + QA before Phase 5. |
| YES | 2+ | Dispatch `agent-orchestrator` with the confirmed goal + criteria. The orchestrator's run IS your Phase 2-3. You still own Phase 4 (verify its evidence) and Phase 5 (deliverable). |

If scope expands mid-execution (new domain or new trust boundary discovered), stop and re-run
this gate — promotion is mandatory, per `rules/workflow-routing.md`.

---

## Phase 1 — Intake (one round, then stop asking)

Ask ONLY the fields recon could not answer, in **one message** (one batched question round —
use a question tool if available, otherwise inline). Typical residual questions: acceptance
ambiguity ("web UI or CLI?"), forbidden areas, deadline. If recon answered everything, skip
questions entirely.

Then **confirm the goal and success criteria** in one message:

```
Goal: <one sentence>

Success criteria (all must pass):
1. <measurable outcome>
2. <measurable outcome>
3. <measurable outcome>
N. Deliverable runs without errors end-to-end — verified by: <exact command>
N+1. Evidence produced: <test output file / screenshot / URL>
```

Every criterion must name the command or artifact that will prove it. "Works correctly" is not
a criterion; "`make test` exits 0" is.

**Headless rule:** if the session is non-interactive, or the user already pre-authorized
("don't ask, just build", a scheduled run, a one-shot prompt), the confirmation message is
best-effort, never a gate — write the goal + criteria to the state file as the confirmation of
record and proceed immediately. Otherwise wait for the user's confirm/adjust, then proceed and
do NOT ask again unless you hit a terminal blocker (defined in Phase 3, Rule 4).

---

## Phase 2 — Plan (externalized, verifiable)

1. Write the plan to the state file `.goal/state.md` in the repo root (add `.goal/` to
   `.gitignore` if not ignored). The state file holds: goal, success criteria, task list with
   statuses and dependencies, key decisions, follow-ups, blockers. Mirror it into your
   task-tracking tool if one is available — **the state file is canonical.** Update it after
   every task; after any context compaction or resume, re-read it before doing anything else.
   Never re-derive the plan from memory.
2. Each task gets four fields: **what** (imperative), **files** (paths it may touch),
   **depends-on** (task ids), and **verify** — the proof that it worked, chosen from this
   ladder (highest rung that applies):
   - **Executable command** — `make test`, `npx tsc --noEmit`, `curl -sf localhost:8080/health`
   - **Content assertion** — `grep -q 'retention: 90d' docs/adr/012.md` (for docs/config tasks)
   - **Reviewer check** — dispatch `code-reviewer` on the diff (for judgment tasks like
     refactors with no behavior change)
   Never invent a vacuous verify (`ls`, `cat`) to satisfy the rule — pick the honest rung.
3. Order tasks by dependency. If 2+ tasks are independent and substantial, dispatch them in
   parallel per `skills/dispatching-parallel-agents`.
4. For goals spanning many files or phases, invoke the `writing-plans` skill instead of an
   ad-hoc list — then execute that plan.

---

## Phase 3 — Build → Test → Debug loop

### The Operating Rules (non-negotiable)

1. **Auto-run.** If you're not blocked, don't ask — proceed to the next task.

2. **TDD where code is the deliverable.** Invoke the `test-driven-development` skill for
   feature/bugfix tasks. Write the failing test first; it becomes the task's verify command.

3. **Verify every task with its declared proof.** Run it and READ the output. Log one line:
   task, status (done / concern / blocked), command, and the decisive line of output (e.g.,
   `12 passed`). Never write "should work", "looks correct", or a status without output behind
   it — per `skills/verification-before-completion`, evidence precedes assertion.

4. **Debug systematically; fail fast on real walls.** On any failure, invoke
   `systematic-debugging` — reproduce, isolate root cause, fix, re-run verify. Do not
   pattern-match a "typical fix". If **2 fix attempts on the same issue fail**:
   - Mark the task blocked in the state file, AND mark every task that depends on it
     (transitively) blocked too. Do not build on a broken foundation.
   - Continue only with tasks that are genuinely independent of the blocked chain.
   - If the blocked chain contains a success criterion (critical path), or you hit a
     review-loop cap from `rules/review-loop-limits.md`, that IS the terminal blocker this
     skill's "no questions" ethos yields to: stop building, go to Phase 4, and deliver with a
     Blockers section. Failing fast with a clear report beats burning the run.

5. **No placeholders.** No TODO, no stub, no mock data left in delivered paths, no "implement
   later". Every component is complete before its task is marked done.

6. **Stay on goal.** Out-of-scope improvements go in the state file as follow-ups. Scope
   expansion re-runs the Phase 0 routing gate.

7. **Use every tool.** Terminal, MCP, file reads, curl, browser automation — whatever produces
   real evidence. Don't simulate results by inspection alone.

8. **Match the codebase.** Follow the project's existing conventions and lint/format/type
   commands (from recon). Run the project's lint + typecheck as part of verify for any task
   that touches code.

### Context-survival rule

After completing each task, update the state file BEFORE starting the next. If you ever notice
missing context (post-compaction, resumed session), re-read `.goal/state.md` first.

---

## Phase 4 — Self-review gate (before claiming done)

Do not go straight from "last task done" to the deliverable. Run this gate:

1. **Criteria audit.** Re-read every success criterion from the state file. For each, re-run
   its proving command NOW (not from memory of an earlier run) and record the output.
2. **Adversarial pass.** Review the full diff (`git diff` / changed-file list) as a hostile
   reviewer: dead code, debug prints, hardcoded values that should be config, secrets, broken
   envelope/pattern rules from `rules/patterns.md`, unverified edge cases. Fix what you find.
   For substantial changes, dispatch the `code-reviewer` agent instead of self-review and
   address its material findings (max 2 fix rounds, per `rules/review-loop-limits.md`).
3. **End-to-end run.** Invoke `validate-feature` where applicable — run the deliverable as the
   user would (fresh command, real service, real browser), not just unit tests.

Only when the gate passes may you write the deliverable. If a criterion cannot be met, it goes
under Blockers — never quietly reworded.

---

## Phase 5 — Final Deliverable

```
## Goal Complete: <goal description>

### Success Criteria
- [criterion 1]: met — <command + decisive output line>
- [criterion 2]: met — <evidence>
- ...

### Files Changed
- Created / Modified / Deleted: <paths>

### How to Run / Test / Deploy
<exact commands>

### Evidence
<test output path, screenshot path, or URL — real artifacts, re-run in Phase 4>

### Limitations & Follow-ups
<anything deferred, known gaps, follow-ups from the state file>

### Key Decisions
<non-obvious choices made and why>
```

If any criterion is unmet due to a blocker:

```
### Blockers
- <what failed, both fix attempts tried, what needs to change to unblock>
```

---

## What "Done" Means

- Every success criterion re-verified in Phase 4 with fresh evidence
- The deliverable runs without intervention on first try
- Someone else can pick it up from the deliverable section alone
- The state file's follow-ups and blockers are surfaced, not swallowed

"I think it works" is not done. "Tests pass and here's the output" is done.
