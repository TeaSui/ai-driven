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

## How this index stays honest

- When a plan ships, move the plan doc to `archive/<phase>/` and write a retrospective.
- When a feature ships, update `vision/02-production-readiness.md` (flip the relevant checkbox).
- When architecture changes, update `architecture/` (the snapshot) — not `vision/03-architecture-vision.md` (the target).
- A stale doc is worse than no doc. If you can't confirm a claim, flag it with `**STALE** — <date>` rather than leave a silent lie.
