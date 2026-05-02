# Configuration

Runtime configuration is environment-variable-driven. **Source of truth is
`src/ai_driven/config.py`** — this doc is a pointer, not a duplicate.

## How config is loaded

`config.py` uses pydantic-settings with prefix `AI_DRIVEN_` and auto-loads
`.env` + `.env.local` (in that order; `.env.local` wins). Any setting
documented below is readable as `get_settings().<field>` in Python or
`AI_DRIVEN_<FIELD_UPPERCASE>` as an env var.

## Required env (minimum to boot)

| Env var | Purpose |
|---|---|
| `AI_DRIVEN_CLAUDE_PROVIDER` | `anthropic` \| `bedrock` \| `mock` |
| `AI_DRIVEN_ANTHROPIC_API_KEY` | Required when provider=anthropic |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | Required when provider=bedrock or any AWS service is in use |
| `AI_DRIVEN_POSTGRES_*` | Host / port / user / password / db |
| `AI_DRIVEN_REDIS_URL` | `redis://host:port/db` |

## SQS queues (all 5 required in prod; debug mode allows partial)

| Env var | Purpose |
|---|---|
| `AI_DRIVEN_SQS_TASK_QUEUE_URL` | Coding-agent queue |
| `AI_DRIVEN_SQS_REVIEW_QUEUE_URL` | Review-agent queue |
| `AI_DRIVEN_SQS_DEVOPS_QUEUE_URL` | DevOps-agent queue |
| `AI_DRIVEN_SQS_ORCHESTRATOR_QUEUE_URL` | Phase 2 orchestrator worker |
| `AI_DRIVEN_SQS_QA_QUEUE_URL` | Phase 2 post-coding QA worker |

## Orchestrator mode

| Env var | Values | Default | Effect |
|---|---|---|---|
| `AI_DRIVEN_ORCHESTRATOR_MODE` | `shadow` \| `live` \| `off` | `shadow` | `shadow` runs specialists for telemetry; `live` enforces strict halts; `off` skips orchestrator entirely |
| `AI_DRIVEN_ORCHESTRATOR_TREAT_ERRORS_AS_PASS` | `true` \| `false` | `true` | Error-verdict specialists pass through instead of halting |

See [`runbooks/06-orchestrator.md`](../runbooks/06-orchestrator.md) for the
operational meaning of each mode and cutover procedure.

## External integrations (optional)

| Env var | Purpose |
|---|---|
| `AI_DRIVEN_JIRA_*` | Jira webhooks + ticket updates |
| `AI_DRIVEN_GITHUB_TOKEN` | GitHub API access |
| `AI_DRIVEN_BITBUCKET_*` | Bitbucket alternative SCM |
| `AI_DRIVEN_SLACK_*` | Notifications |
| `AI_DRIVEN_DATADOG_ENABLED` | Observability |
| `AI_DRIVEN_VOYAGE_API_KEY` | RAG embeddings |

## Local development

Copy `.env.local.example` (if present) or seed your own:

```bash
cp .env.local.example .env.local
# edit secrets
set -a && source .env.local && set +a
.venv/bin/uvicorn ai_driven.main:app --host 127.0.0.1 --port 8888
```

Debug mode (`AI_DRIVEN_DEBUG=true`) bypasses auth and processes tasks
inline instead of through SQS. **Never set debug=true in production.**

## Not documented here

- Per-agent model IDs / cost ceilings — see `src/ai_driven/config.py`.
- Secrets handling in prod — see [`policies/sbom.md`](../policies/sbom.md)
  and (future) `policies/secrets.md`.
- Team-scoped settings (claude_provider override, max_concurrent_tasks) —
  stored per-row in `teams.settings_json`, not env vars.
