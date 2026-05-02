# Roadmap — 90 Days to GA, then 12 Months

**Reference date:** 2026-05-01 (post Phase 2 close; live mode on `feature/orchestrator-phase1`)
**Current state:** Design-partner pilot (1 team), Next.js UI substantially complete, 4 core agents + 6 specialists + orchestrator/QA gate, evaluation loop live. Phase 2 shipped strict gating + post-coding QA + admin override + reapers (see `docs/phase2-retrospective.md`). Findings in `docs/review/findings-final.md` still contain unfixed P1s that block GA.

## Now (weeks 0–2) — Close the findings

Goal: every P1 from the review is either fixed or has a dated mitigation.

| Item | Owner | Doc | Status |
|---|---|---|---|
| Doc truth-drift (arch/02, 05, 07; state machine) | Docs | Already applied | ✅ |
| WS URL hardcoded port → env-var + proxy | Frontend | `review/findings-final.md §2.1` | ✅ (frontend), proxy deploy pending |
| Wire Pause/Resume/Stop on real tasks | Frontend | `review/findings-final.md §2.2` | ✅ |
| Demo fallback gated behind `NEXT_PUBLIC_DEMO_MODE` | Frontend | `review/findings-final.md §3.7` | ✅ |
| SWR stops polling on terminal task statuses | Frontend | `review/findings-final.md §5.1` | ✅ |
| Severity taxonomy migration (enum + CHECK + normalize) | Backend + Data | `implementation/07-severity-taxonomy-migration.md` | 🔲 plan written |
| WebSocket authz + Next.js middleware + /login | Backend + Frontend + Security | `implementation/08-auth-and-ws-security.md` | ◐ partial — `/login` + session tokens shipped; WS authz + middleware still pending |
| `cancelled` terminal state replaces `failed` on `/stop` | Backend + UI | `implementation/08` §Phase 1.5 | ✅ |
| Model IDs — bump Opus 4.6 → 4.7 + re-benchmark | AI Eng | `config.py:39` | ✅ landed (re-benchmark still TODO) |
| **Phase 2 orchestrator + specialists + QA gate + admin override** | Backend + Frontend | `docs/plans/2026-05-01-orchestrator-phase-2.md`, `docs/phase2-retrospective.md`, `docs/runbooks/orchestrator-phase2.md` | ✅ live mode flipped 2026-05-01 (PR #33) |

**Exit criteria:** every row green; zero P1 findings open; `successRate` metric excludes `cancelled`; debug banner visible when `AI_DRIVEN_DEBUG=true`.

## Next (weeks 3–6) — Launch-ready polish

| Theme | Deliverable | Status |
|---|---|---|
| Observability | Budget burn-down bar in header; disable "New run" at 100% cap; DLQ & stuck-task view under `/admin/runs`. | ◐ DLQ + stuck-task reapers ✅ (T24); budget bar + dispatch-disable ✅; dashboard view of DLQ pending |
| Safety | Emergency-stop admin toggle (drains queues); per-repo auto-merge opt-in toggle with human-required stage indicator. | 🔲 |
| Reviews UX | Split `/dashboard/reviews` into "Awaiting me" / "Awaiting others" / "Merged" with keyboard shortcuts (j/k/a/r). | 🔲 |
| Cost | Per-team daily/monthly spend dashboard with model-routing breakdown. | ◐ per-artifact cost attribution ✅ (T-A2); dashboard surface pending |
| Evaluation | Backfill eval scores for last 30 days of completed tasks; surface 7-day rolling weighted avg on overview hero. | 🔲 |
| Accessibility | axe-core CI check, aria-live on streaming panels, visible focus rings, kill remaining `alert()`. | 🔲 |
| Onboarding | `/onboarding` wizard: connect repo → install GitHub app → configure webhook → pick default agent. | 🔲 |
| Specialists UI | Task-detail "Specialists" tab with per-role artifact cards, QA-failure breakdown, admin override button. | ✅ shipped in T25 |
| Dogfood harness | Corpus + harness + markdown report for measuring specialist error rate, override rate, cost/task. | ✅ shipped in T27 (`scripts/phase2_dogfood_*`) |

**Exit criteria:** a new customer can go from "landed on the marketing page" to "first AI-merged PR" in under 30 minutes unassisted.

## Later (weeks 7–12) — GA & pricing

| Theme | Deliverable |
|---|---|
| Pricing & billing | Per-seat + metered overage via Stripe; in-app usage meter; invoice email. |
| SSO federation | SAML for mid-market customers; SCIM provisioning. |
| Workflow composition | Visual editor for multi-agent pipelines; YAML export; trigger on Jira/PR/alert events. |
| SDK | Public tool-registry SDK in Python + TypeScript so customers can register their own tools. |
| Marketplace | Share anonymized workflows between teams; top-20 templates seeded by us. |
| SOC2 Type I | Security controls audit; logging & retention policies locked; DPA available. |
| Regional deploy | `ap-southeast-1` primary, `eu-west-1` for EU-resident customers. |

**Exit criteria:** GA announcement; 10 paying customers; <1% monthly churn in closed beta cohort.

## Beyond (year 2)

- Cross-repo refactor agent (monorepo-scale change over 100+ packages).
- Security-patch bot (Dependabot, but reviews, tests, and merges the upgrade too).
- Fine-tuning per team on their reviewed code (opt-in).
- Self-hosted control plane for customers with strict data-residency.
- Mobile companion app for oncall (approve/merge/rollback from phone).

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Anthropic outage leaves platform dark | Circuit breaker → Bedrock fallback → degrade to queued state. Eval suite against Bedrock+Anthropic keeps both warm. |
| Model regression on an upgrade | Version-pinned in `config.py` + canary cohort of 10% of tasks on the new model; rollback if eval score drops >0.5. |
| Cross-tenant data leak | `implementation/08` is a hard prerequisite for customer #2. Automated daily test: a `teamA` token hitting `teamB` IDs returns 404s. |
| Runaway token spend | $100/day hard cap enforced in code (blocks dispatch, not warning). Per-team quotas in year 2. |
| Customer loses trust after a bad merge | Human-required default + reversible merges + immediate emergency stop. |
| Scope creep into "write any code" | North-star is routine-ticket throughput and merged-PR correctness, not prompt flexibility. |

## What we will say "no" to (this year)

- Visual workflow editor before GA (nice-to-have, not needed).
- Competing with Cursor/Copilot inside the IDE.
- Supporting languages outside Python/TS/Java/Go/Kotlin before year 2.
- Self-hosted on-prem for design partners — cloud-only in year 1.
