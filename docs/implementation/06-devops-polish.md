# Sprint 6: DevOps Assistant & Production Polish

**Status: COMPLETE**

## Goal

Complete the DevOps Assistant, harden for production (multi-tenancy, rate limiting, quotas), build DataDog dashboards, and prepare CDK deployment pipeline.

## Prerequisites

- Sprints 1-5 complete
- DataDog account with API/App keys configured
- AWS account with CDK bootstrapped

---

## Tasks

### 6.1 DevOps Agent — Tool Implementation

**File: `src/ai_driven/agents/devops/agent.py`**

- [x] Register tools: query_datadog_logs, query_datadog_metrics, get_recent_deployments, get_service_health, create_jira_incident, notify_oncall, suggest_rollback

### 6.2 DevOps Agent — DataDog API Client

**New file: `src/ai_driven/integrations/datadog_client.py`**

- [x] `DataDogClient` class using httpx
- [x] `search_logs`, `query_metrics`, `get_service_summary`, `get_active_monitors`
- [x] Rate limiting and retry logic

### 6.3 DevOps Agent — Incident Workflow

**New file: `src/ai_driven/agents/devops/incident.py`**

- [x] `IncidentReport` dataclass with severity P1-P4
- [x] `to_jira_description()` and `to_slack_message()` formatters
- [x] `from_dict()` and `parse_from_text()` parsers

### 6.4 Multi-Tenancy Hardening

**File: `src/ai_driven/auth/middleware.py`**

- [x] Resolve team from API key (cached in Redis 5 min)
- [x] Inject `team_id` into request state
- [x] Audit logging for API calls per team

### 6.5 Rate Limiting & Quotas

**New file: `src/ai_driven/auth/rate_limiter.py`**

- [x] Redis sliding window rate limiter
- [x] Configurable: API requests/hour, token quotas/day, concurrent tasks
- [x] Returns `429 Too Many Requests` with `Retry-After`
- [x] Graceful degradation without Redis

### 6.6 Cost Tracking & Budgets

**New file: `src/ai_driven/billing/tracker.py`**

- [x] Track Claude API cost per team per day
- [x] `calculate_cost()` with per-model pricing
- [x] Budget alerts (warn at 80%, block at 100%)
- [x] `GET /api/admin/usage` endpoint

### 6.7 DataDog Dashboards

**New file: `docs/datadog/dashboards.json`**

- [x] Dashboard: "AI-Driven Platform Overview" with 9 widgets
- [x] Monitors: failure rate, API error rate, budget warnings

### 6.8 Prompt Caching Optimization

**File: `src/ai_driven/claude/provider.py`**

- [x] `cache_control` breakpoints on system prompts (>1024 tokens)
- [x] `cache_control` breakpoints on tool schemas (last tool)
- [x] Cache hit/miss logging
- [x] No mutation of original data structures

### 6.9 Error Recovery & Resilience

**File: `src/ai_driven/claude/provider.py`**

- [x] `CircuitBreaker` — opens after 5 failures, recovers after 60s
- [x] `ResilientProvider` — wraps providers with circuit breaker + model fallback
- [x] Fallback chain: Opus → Sonnet → Haiku
- [x] Health checks for PostgreSQL, Redis, Claude in `GET /health`

### 6.10 CDK Deployment Pipeline

**New file: `infrastructure/stacks/pipeline_stack.py`**

- [x] CodePipeline: GitHub source → build → test → deploy
- [x] ECR repositories for API and Worker images
- [x] CodeBuild with Docker builds and pytest
- [x] Manual approval gate before production

### 6.11 CDK — Secrets Management

- [ ] Store API keys in Secrets Manager (deferred — requires AWS account)
- [ ] Auto-rotate GitHub tokens (deferred)

### 6.12 Production Config

- [x] `docker/Dockerfile.api` — multi-stage build, non-root user, health check, gunicorn
- [x] `docker/Dockerfile.worker` — multi-stage build, non-root user
- [ ] ECS auto-scaling (configured in compute_stack, needs tuning in production)

### 6.13 Tests

- [x] `tests/unit/test_devops_agent.py` — tool registration, tool execution
- [x] `tests/unit/test_incident.py` — incident report creation, validation, serialization
- [x] `tests/unit/test_rate_limiter.py` — defaults, no-redis fallback
- [x] `tests/unit/test_cost_tracker.py` — cost calculation, budget status
- [x] `tests/unit/test_prompt_caching.py` — system prompt and tool caching
- [x] `tests/unit/test_circuit_breaker.py` — circuit breaker states, resilient provider
- [x] `tests/unit/test_health_check.py` — dependency health checks

---

## Verification

```bash
# All tests: 425 passing
pytest tests/ -v

# DataDog dashboard definition
cat docs/datadog/dashboards.json

# CDK synth
cd infrastructure && cdk synth
```

## Files Created/Modified This Sprint

| Action | File |
|--------|------|
| Modify | `src/ai_driven/agents/devops/agent.py` |
| Create | `src/ai_driven/agents/devops/incident.py` |
| Create | `src/ai_driven/integrations/datadog_client.py` |
| Modify | `src/ai_driven/auth/middleware.py` |
| Create | `src/ai_driven/auth/rate_limiter.py` |
| Create | `src/ai_driven/billing/tracker.py` |
| Modify | `src/ai_driven/claude/provider.py` (caching + resilience) |
| Modify | `src/ai_driven/api/health.py` (dependency checks) |
| Modify | `src/ai_driven/api/admin/router.py` (usage endpoint) |
| Create | `infrastructure/stacks/pipeline_stack.py` |
| Modify | `docker/Dockerfile.api` (production hardening) |
| Modify | `docker/Dockerfile.worker` (production hardening) |
| Create | `docs/datadog/dashboards.json` |
| Create | `tests/unit/test_devops_agent.py` |
| Create | `tests/unit/test_incident.py` |
| Create | `tests/unit/test_rate_limiter.py` |
| Create | `tests/unit/test_cost_tracker.py` |
| Create | `tests/unit/test_prompt_caching.py` |
| Create | `tests/unit/test_circuit_breaker.py` |
| Create | `tests/unit/test_health_check.py` |
