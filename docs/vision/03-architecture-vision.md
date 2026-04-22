# Architecture Vision — Where the Platform is Heading

Draft: **2026-04-22.** Complement to the snapshot architecture in `docs/architecture/`. This document describes intent, not current reality. See "Deltas from today" at the end for what needs to change.

## Core principles (unchanged from today)

1. **Event-driven by default** — nothing is computed that isn't streamed.
2. **Claude-agnostic** — Anthropic Direct, Bedrock, Mock behind one circuit-breaker boundary.
3. **Single event spine** — Redis pub/sub is the only way runtime state reaches the UI.
4. **Persist decisions, not process** — every AI decision (tool call, prompt, evaluation) is stored; intermediate model reasoning is not retained beyond 24 h.
5. **Tool registry is the contract** — agents are defined by which tools they can call; the rest is implementation detail.

## Target topology (12 months out)

```
Browser ─▶ ALB ─▶ Next.js (ECS) ─▶ FastAPI Gateway
                                      │
                                      ├── Agent Workers (ECS, per-agent-type pool)
                                      │     └─ Claude Abstraction
                                      │           ├─ Anthropic Direct
                                      │           └─ Bedrock
                                      │
                                      ├── Event Spine (Redis cluster)
                                      ├── Cost Ledger (Redis, streamed to PG)
                                      ├── PostgreSQL (Aurora Serverless v2 + pgvector)
                                      ├── Workflow Engine (Step Functions) — NEW
                                      ├── Tool Registry Service — NEW
                                      └── Eval Service (separate worker) — NEW

External: GitHub | Jira | Slack | DataDog | Voyage AI | Cognito (IdP) | Stripe (billing)
```

Changes vs. today:

| Area | Today | Target | Why |
|---|---|---|---|
| Workflow agent | In-process orchestration | AWS Step Functions | Durable state, retries, human-approval steps as first-class transitions |
| Tool registry | Per-agent Python file | Dedicated service + public SDK | Customers can register their own tools; versioned; scoped per team |
| Evaluation | Fire-and-forget inside agent worker | Separate worker with its own queue | Lets agents scale independently; keeps eval latency off the critical path |
| Auth | API key + `AI_DRIVEN_DEBUG` bypass | Cognito + JWT + team-scoped middleware | Multi-tenancy; SSO |
| RAG index | Per-repo, pgvector | Per-team namespace, pgvector + eventual Lucene BM25 migration to OpenSearch | Cross-repo search quality |
| Cost tracking | Redis INCR | Redis stream → PG materialized view | Real-time + historical without losing resolution |
| Debug mode | Global env var | Per-team feature flag | Safer toggle in production demos |

## Agent interaction pattern (future)

All agents speak in a common event protocol (already partially implemented):

```
prompt_sent
├─ iteration
│   ├─ streaming_chunk (buffered 200ms / 500 chars)
│   ├─ tool_call_start
│   ├─ tool_call_end
│   └─ text_delta
├─ rag_indexing_start / rag_indexing_complete
├─ cache_hit / cache_write
├─ eval_start / eval_complete   (NEW)
├─ human_approval_required      (NEW — pauses workflow)
└─ complete | cancelled | error
```

New events:
- `human_approval_required` — workflow engine halts and surfaces an approval gate in the UI and in Slack.
- `eval_start/complete` — evaluation becomes a first-class, streamable stage.

## Multi-tenancy model (target)

Every row in every table carries `team_id`. Every read goes through a repository method that injects the team filter from the request context. No code path is allowed to select by primary key without a team-scoped `WHERE`.

Enforcement: pytest fixture `assert_team_scoped` wraps every repo method; CI rejects a PR that adds an unscoped read.

## Workflow composition (year 2)

Customers describe pipelines declaratively:

```yaml
# .ai-driven/workflows/critical-bug.yaml
on:
  jira_transition:
    to: "Ready"
    label: "sev-critical"
steps:
  - coding:
      model: claude-opus-4-7
      write_scope: ["src/**/*.py"]
  - review:
      required_approvers: 1
      human_gate: true
  - deploy:
      environment: staging
      auto_merge: false
```

Runs on Step Functions; each step is the existing agent dispatched with scoped inputs.

## Data flow & retention

| Data | Store | Retention |
|---|---|---|
| Task records | PostgreSQL | Forever (tenant-isolated) |
| Event log | Redis pub/sub + PostgreSQL | 24 h in Redis (as today); PG mirror for completed tasks permanent |
| Streaming chunks (text_delta) | Redis only | 24 h |
| RAG embeddings | pgvector | Until commit SHA changes |
| Prompt & tool inputs | PostgreSQL (`conversation_records`) | 90 days then purged per customer opt-in |
| Evaluations | PostgreSQL | Forever |
| Cost ledger | Redis + PG materialized view | 13 months |
| Logs (DataDog) | DataDog | 30 days hot, 90 days cold |

## Model routing (target)

| Task type | Model | Rationale |
|---|---|---|
| Classification (intent, severity inference) | Haiku 4.5 | Cheap, fast |
| Coding (small diff ≤ 100 LoC) | Sonnet 4.6 | Good enough, ~3× cheaper than Opus |
| Coding (large diff / planning) | Opus 4.7 | Plan quality pays for itself |
| Review | Sonnet 4.6 | Default; escalate to Opus on files > 500 lines |
| DevOps triage | Sonnet 4.6 | Good enough, speed matters |
| Evaluation | Haiku 4.5 | Cheap, deterministic rubric |

Routing is configured once in `src/ai_driven/config.py` and exposed as a feature-flag override per team (year 2).

## Failure modes — contracts

| Failure | Contract |
|---|---|
| Claude timeout | Circuit breaker trips after 3 consecutive failures → Bedrock. After 3 more → Mock (and the task surfaces a clear "Claude unavailable" status; does not fail). |
| Redis down | WebSocket sends a one-shot "events_paused" to clients; task continues; events buffered to PG and replayed on recovery. |
| Postgres down | Workers pause; in-flight tasks time out cleanly; recovery reprocesses from SQS. |
| Voyage AI down | Existing RAG index keeps serving; new indexing jobs queue until recovery. |
| Cost cap hit | Dispatch endpoint returns 429 with `Retry-After: <seconds-until-midnight>`; worker drains in-flight but refuses new. |

## Public surfaces (year 2)

- **REST API**: OpenAPI-documented; `/v1/` prefix; semantic-versioned.
- **Tool registry SDK**: `pip install ai-driven-sdk` / `npm install @ai-driven/sdk`. Customers register tools, deploy them as FaaS, and the platform calls them over HTTPS with a signed request.
- **Webhooks out**: `task.completed`, `pr.merged`, `incident.triaged` — subscriber URLs per team.

## Deltas from today

Near-term (feeds `roadmap.md` Now/Next):
1. Cognito + middleware.ts + WS authz (`implementation/08`).
2. Severity taxonomy unification (`implementation/07`).
3. `cancelled` terminal state.
4. Emergency-stop / per-team budget UI.

Mid-term (roadmap Later):
5. Step Functions workflow engine replaces in-process orchestration.
6. Eval service separated from agent worker.
7. Per-team feature flags replace the global `AI_DRIVEN_DEBUG`.

Long-term (year 2):
8. Tool registry SDK + customer-hosted tools.
9. Visual workflow editor.
10. Self-hosted control plane.

Each delta should get its own `docs/implementation/NN-*.md` plan when it's next-up.
