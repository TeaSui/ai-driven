# Documentation index

Last verified: 2026-05-01 (post Phase 2 docs reorganization).

## Where to start

| You want to … | Go here |
|---|---|
| Understand the product & why it exists | [`vision/`](vision/) — product thesis, roadmap, production-readiness checklist, target architecture |
| Understand how the system is built today | [`architecture/`](architecture/) — ER diagram, topology, state machine, sequence diagrams, threat models, MCP integration |
| See what features the platform ships | [`features/`](features/) — one document per feature, end-to-end |
| Operate the system | [`runbooks/`](runbooks/) — per-task operator procedures (numbered 01-06) |
| Find open work / GA blockers | [`review/findings-final.md`](review/findings-final.md), [`vision/01-roadmap.md`](vision/01-roadmap.md) |
| Read a planned-but-unshipped change | [`plans/`](plans/) (empty today) + [`implementation/`](implementation/) (active plans: 07 severity, 08 auth+WS) |
| Look up past decisions | [`retrospectives/`](retrospectives/) + [`archive/`](archive/) |

## Directory map

```
docs/
├── README.md                      ← you are here
├── architecture/                   ← how it works today (diagrams, threat models, MCP)
├── vision/                         ← where we're heading (vision, roadmap, readiness, target)
├── features/                       ← one file per feature, end-to-end
├── implementation/                 ← active implementation plans (numbered)
├── plans/                          ← active multi-step plans (dated)
├── retrospectives/                 ← per-phase wrap-ups (dated)
├── runbooks/                       ← operator procedures (numbered)
├── review/                         ← findings-final.md (GA blocker tracker)
├── ops/                            ← configuration + observability assets
├── policies/                       ← compliance + governance
└── archive/                        ← shipped plans, historical baselines
    ├── phase-1/
    ├── phase-2/
    │   └── briefs/                 ← t22-t28 task briefs
    ├── implementation/             ← shipped 00-06, 09-12, 14
    └── misc/
```

## Naming conventions

- **Dated docs** (plans, retrospectives): `YYYY-MM-DD-<topic>.md` — e.g. `retrospectives/2026-05-01-phase-2.md`.
- **Numbered sequences** (implementation, runbooks, features): `NN-<topic>.md` starting at `00-overview.md` or `01-...`.
- **Task briefs inside a multi-task plan**: `tNN-<topic>-brief.md`, archived alongside the plan when shipped.
- **One purpose per directory.** Don't create a top-level dir for a single file — nest into an existing semantic home.

## Cross-cutting artifacts

- Root [`CHANGELOG.md`](../CHANGELOG.md) — release notes, grouped by phase.
- [`vision/02-production-readiness.md`](vision/02-production-readiness.md) — GA gate checklist.
- [`architecture/04-task-state-machine.md`](architecture/04-task-state-machine.md) — authoritative task status diagram (updated post Phase 2 via `runbooks/06-orchestrator.md`).

---

## Update contract — what to touch after you ship

Whenever you merge a PR that changes code behavior, walk this table. If the PR's diff matches a trigger, the listed docs MUST be updated in the same PR (or a follow-up PR filed before the code merges to `main`).

### Triggers → docs to update

| If your PR does this … | Update these docs (minimum) |
|---|---|
| **Adds/removes/renames a DB table or column** | `architecture/01-database-er.md` (ERD + table description + Status Flow if status column changed) |
| **Adds a status value to `ck_tasks_status`** | `architecture/01-database-er.md` + `architecture/04-task-state-machine.md` (add state + transitions + backend mapping block) |
| **Changes status → stage/stageStatus projection** (`admin/router.py` `_STATUS_TO_STAGE*` dicts) | `architecture/04-task-state-machine.md` ("Backend Mapping" block) + `runbooks/06-orchestrator.md` ("Timeline stage mapping" table) |
| **Adds/renames an agent type or specialist** | `architecture/02-system-architecture.md` (Agents subgraph + Key Data Flows) + `features/` if user-facing |
| **Adds/renames an SQS queue or changes routing** | `architecture/02-system-architecture.md` (Infra subgraph + flow edges) + `ops/configuration.md` (env-var table) |
| **Adds/changes an `AI_DRIVEN_*` env var** | `ops/configuration.md` (required or integrations table) |
| **Adds/changes an env-stamped message shape** (e.g. `orchestrator_mode` on SQS) | `runbooks/06-orchestrator.md` (env flags section) |
| **Adds a new admin or public endpoint** | `architecture/02-system-architecture.md` (API subgraph) + `features/` if user-facing; if `require_admin`-gated, also `vision/02-production-readiness.md` §2 |
| **Adds/changes a DataDog counter or alert** | `runbooks/06-orchestrator.md` (Dashboards + Alerting tables) + `vision/02-production-readiness.md` §3 Observability |
| **Adds a migration** | Verify `architecture/01-database-er.md` is still accurate. If down_revision chain branches, document the ordering in the migration's docstring. |
| **Adds a new feature end-to-end** | `features/NN-<name>.md` (new file) + `vision/02-production-readiness.md` (flip the checkbox this feature enables) |
| **Closes a GA blocker** | `review/findings-final.md` (strike through with ✅ FIXED + evidence path) + `vision/02-production-readiness.md` (flip checkbox) |
| **Ships a multi-task plan** | Move `plans/<plan>.md` + every `plans/t*-brief.md` → `archive/<phase>/` + write `retrospectives/<date>-<phase>.md` + append `CHANGELOG.md` |
| **Deprecates a script / module / endpoint** | `CHANGELOG.md` (Deprecated section) + leave a pointer comment in the code + if it's a script, rename with `_archive` suffix |
| **Changes orchestrator mode semantics or rollout procedure** | `runbooks/06-orchestrator.md` + `archive/phase-2/cutover-smoke-results.md` if cutover-related |

### Rules of thumb

1. **Docs belong in the same PR as the code change that makes them true.** Trailing "docs update" PRs rot — they get deprioritized and drift.
2. **Update the snapshot (`architecture/`), not the target (`vision/`).** The target only changes when strategy changes.
3. **Checkboxes in `vision/02-production-readiness.md` are promises.** Flipping one ✅ without the code actually being true is the single highest-trust-cost mistake in this repo. When in doubt, leave it 🔲 with a note.
4. **Strike through, don't delete.** Closed items in `review/findings-final.md` and `CHANGELOG.md` stay as history.
5. **A stale doc is worse than no doc.** If you can't confirm a claim, flag it with `**STALE** — <date>` inline rather than leaving a silent lie.
6. **One purpose per directory.** Don't create a top-level dir for one file — nest into an existing semantic home (see naming conventions above).
7. **Archive when shipping.** A plan that shipped is not an active plan; move it to `archive/`. Rule of thumb: if `git log -- <plan>.md` has no activity for a full phase, it's archivable.

### PR-checklist snippet (copy into your PR template)

```markdown
## Docs touched
- [ ] `architecture/` — if DB schema, agent topology, or status machine changed
- [ ] `ops/configuration.md` — if env vars changed
- [ ] `runbooks/` — if operator procedure changed (state machine, counters, alerts, rollback)
- [ ] `features/` — if a user-visible feature was added / renamed
- [ ] `vision/02-production-readiness.md` — if a checklist item moved state
- [ ] `review/findings-final.md` — if a finding closed or a new one surfaced
- [ ] `CHANGELOG.md` — always, with release-phase section

## Archive check
- [ ] If this PR ships a named plan, the plan doc + briefs are moved to `archive/<phase>/`
- [ ] If this PR closes a phase, `retrospectives/<date>-<phase>.md` is written
```

### When to re-verify from scratch

- Every ~4 weeks on a slow day, or
- At the close of a named phase, or
- When `review/findings-final.md` accumulates more than 15 ◐/🔲 items.

The re-verification walks every active doc, greps for claims against current code, and strikes through / updates / flags stale.

