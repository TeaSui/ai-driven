# Strategic Output Persistence

Strategic agents persist outputs to `docs/` so they survive across sessions:
- **TechLead:** module READMEs (primary) + `docs/contracts/` (full runs only, not (contract)-mode / "Stop after Phase 2" dispatches) — API contracts, ADRs, data models
- **Security:** `docs/security/` — threat models, STRIDE, implementation rules
- **BA:** `docs/requirements/` — user stories, acceptance criteria

Implementation agents READ these files, not regenerate. Exception: TechLead (contract)-mode dispatches ("Stop after Phase 2", no delegation) write to module READMEs only.
