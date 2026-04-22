# Vision & Forward-Looking Docs

Strategic direction for the AI-Driven Development Platform. Snapshot docs live under `docs/architecture/` (what is); these describe **what should be**.

| Doc | Purpose |
|---|---|
| [00-vision.md](00-vision.md) | Product vision, north-star metrics, principles, 3-year thesis |
| [01-roadmap.md](01-roadmap.md) | Now / Next / Later / Beyond — dated, owner-tagged |
| [02-production-readiness.md](02-production-readiness.md) | Checklist gating beta → GA promotion |
| [03-architecture-vision.md](03-architecture-vision.md) | Target topology, multi-tenancy, workflow composition, deltas from today |

## Related

- `docs/review/findings-final.md` — what's blocking GA (P1/P2/P3).
- `docs/implementation/07-severity-taxonomy-migration.md` — first P1 fix-up plan.
- `docs/implementation/08-auth-and-ws-security.md` — second P1 fix-up plan.

## How this set is meant to evolve

- **Vision (`00`)** changes yearly, not monthly.
- **Roadmap (`01`)** updates every sprint; dated entries.
- **Production readiness (`02`)** is a living checklist — items flip from 🔲 to ✅ with PR references.
- **Architecture vision (`03`)** changes whenever a major subsystem shifts (e.g. workflow engine, tool registry SDK). Pair every change with a new `docs/implementation/NN-*.md` plan before coding.
