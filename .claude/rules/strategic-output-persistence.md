# Strategic Output Persistence

Strategic agents persist outputs to `docs/` so they survive across sessions:
- **TechLead:** module READMEs (primary) + `docs/contracts/` (full runs only, not (contract)-mode / "Stop after Phase 2" dispatches) — API contracts, ADRs, data models
- **Security:** `docs/security/` — threat models, STRIDE, implementation rules
- **BA:** `docs/requirements/` — user stories, acceptance criteria
- **BA + TechLead (shared):** `docs/domain/` — ubiquitous-language glossary (`glossary.md`) and lightweight domain model (entities, invariants, bounded contexts). BA seeds terms during requirements; TechLead ratifies/extends during contract design. Full-workflow and orchestrator tiers only — fast paths skip it. Specs, contracts, and dispatch prompts reference these named concepts instead of restating definitions (per `rules/single-source-of-truth.md`). For greenfield work, `/new-project`'s research outputs seed the initial glossary.

Implementation agents READ these files, not regenerate. Exception: TechLead (contract)-mode dispatches ("Stop after Phase 2", no delegation) write to module READMEs only.

## Staleness check

Before an implementation agent acts on a strategic doc, it MUST run:

```bash
git log --oneline -1 -- <doc-path>          # last commit that touched the doc
git log --oneline -- <module-path> | wc -l  # total commits on the module since doc was written
```

**Flag for review when any of the following is true:**
- The module has ≥ 5 commits since the doc's last update (`git log <doc-hash>..HEAD -- <module-path>`).
- The doc's last-updated commit is not reachable from the current branch HEAD (i.e., the doc was written on a different branch and never merged).
- The doc contains a `Last updated:` footer date older than 90 days AND the module has any commits since then.

**When flagged:** the implementation agent returns `DONE_WITH_CONCERNS` (not `DONE`) and includes a staleness note:
```
CONCERN: docs/contracts/foo.yaml last updated <date/SHA>; <N> commits to <module> since then.
Recommend re-running TechLead (contract-mode) before the next dispatch that touches this contract.
```

The dispatcher (orchestrator or main session) decides whether to re-run the strategic agent or proceed with the concern noted. Do NOT silently use a stale doc.
