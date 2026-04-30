# Feature 4: AI DevOps Assistant

## Purpose

Automatically triage production incidents from DataDog alerts. When an alert fires, the AI agent correlates logs, metrics, and recent deployments to identify the probable root cause, creates a Jira incident ticket, suggests remediation steps, and notifies the on-call team — cutting mean-time-to-detect (MTTD) from minutes to seconds.

## Who Uses It

- **On-call engineers** — receive enriched incident reports instead of raw alerts
- **SRE team** — see correlated root cause analysis, rollback recommendations
- **Engineering Managers** — track incident frequency, MTTR, and AI-assisted resolution rate

## Expected Result

Given a DataDog alert "API error rate >5% on service `user-api`":
1. Agent queries DataDog logs and metrics for the last 30 minutes
2. Finds: error spike correlates with deployment `v2.3.1` merged 15 minutes ago
3. Creates Jira P2 incident: "API error rate spike after v2.3.1 deployment"
4. Posts Slack message to #incidents with root cause analysis and rollback suggestion
5. Completes in under 1 minute

---

## Full Workflow (Step by Step)

### Step 1: Trigger

**DataDog sends a webhook when an alert fires.**

| Source | Event | Handler |
|--------|-------|---------|
| DataDog monitor | Alert status: Triggered/Warning | `api/webhooks/datadog.py` |
| Manual | `/ai-triage` command in Slack | (future: Slack bot handler) |

**What happens:**
1. Parse alert payload: monitor name, tags, severity, message
2. Deduplicate via `WebhookEventStore` (same alert within 5 min → skip)
3. Extract service name from `service:` tag
4. Look up repository from `repo:` tag or service-to-repo mapping
5. Create `Task` record (status: `QUEUED`, agent_type: `devops`)
6. Publish to devops SQS queue

### Step 2: Worker Picks Up Task

ECS worker polls the devops SQS queue:
1. Receives message, extracts `task_id`
2. Updates task status → `RUNNING`
3. Initializes `DevOpsAgent` with clients (DataDog, Jira, SCM, Slack)

### Step 3: Context Gathering (Tool Calls)

**File: `src/ai_driven/agents/devops/agent.py`**

The agent investigates the incident:

```
Claude API Call: Incident Triage
├── Model: claude-sonnet-4-6
├── System prompt: devops triage instructions
├── Tools: 7 tools (see below)
└── Multi-turn loop
```

**Tool calls the agent typically makes:**

1. `query_datadog_logs(query="service:user-api status:error", from_ts=-1800)`
   → Recent error logs (last 30 min)
   
2. `query_datadog_metrics(query="avg:trace.http.request.errors{service:user-api}", from_ts=-3600)`
   → Error rate trend (last hour)
   
3. `get_service_health(service="user-api")`
   → APM summary + active monitors
   
4. `get_recent_deployments(repo="org/user-api", count=5)`
   → Last 5 merges to main with timestamps and authors

### Step 4: Root Cause Analysis

Claude correlates the data:

1. **Timeline construction**: overlays error spike with deployment timestamps
2. **Pattern matching**: identifies if errors started after a specific deployment
3. **Log analysis**: extracts common error patterns (stack traces, error codes)
4. **Severity assessment**: determines P1-P4 based on blast radius and user impact

**Output:** `IncidentReport` dataclass:
```python
IncidentReport(
    title="API error rate spike after v2.3.1 deployment",
    severity="P2",
    service="user-api",
    root_cause="Error rate increased from 0.1% to 7.2% starting at 14:23 UTC, 
                correlating with deployment v2.3.1 (commit abc123). 
                Logs show NullPointerException in UserProfileHandler.getProfile().",
    impact="~5% of API requests returning 500 errors. Affects user profile loading.",
    remediation_steps=[
        "Rollback to v2.3.0 (last known good deployment)",
        "Investigate null handling in UserProfileHandler.getProfile()",
        "Add null check for optional profile fields"
    ],
    related_deployment="v2.3.1",
    started_at="2026-04-20T14:23:00Z"
)
```

### Step 5: Actions

The agent takes action based on severity:

**5a. Create Jira Incident**

`create_jira_incident(project="OPS", title=..., severity="P2", description=...)`

- Creates a Bug issue in the configured Jira project
- Description includes: root cause, impact, remediation steps, related deployment
- Formatted in Jira wiki markup via `IncidentReport.to_jira_description()`

**5b. Notify On-Call**

`notify_oncall(channel="#incidents", message=...)`

- Sends Slack message formatted via `IncidentReport.to_slack_message()`
- Includes severity label, service name, root cause summary
- Tags relevant team/channel

**5c. Suggest Rollback (if applicable)**

`suggest_rollback(repo="org/user-api", before_timestamp="2026-04-20T14:23:00Z")`

- Identifies the last deployment before the incident started
- Returns: commit hash, author, merge timestamp, PR link
- Agent includes this in the Slack notification

### Step 6: Completion

1. Task status → `COMPLETED`
2. Incident report persisted in PostgreSQL
3. Metrics emitted to DataDog
4. If part of workflow: monitors deployment health window

---

## Severity Classification

| Severity | Criteria | Auto-Actions |
|----------|----------|-------------|
| **P1** — Critical | Service down, >50% errors, data loss | Jira + Slack + page on-call |
| **P2** — High | Degraded, 5-50% errors, feature broken | Jira + Slack + suggest rollback |
| **P3** — Medium | Minor degradation, <5% errors | Jira + Slack |
| **P4** — Low | Warning threshold, no user impact | Jira only |

Severity is determined by Claude based on error rates, user impact scope, and service criticality.

## 7 DevOps Agent Tools

| Tool | What It Does | Data Source |
|------|-------------|-------------|
| `query_datadog_logs` | Search logs by query, service, time range | DataDog Logs API v2 |
| `query_datadog_metrics` | Fetch metric timeseries | DataDog Metrics API v1 |
| `get_recent_deployments` | List recent merges to main | SCM API (GitHub/Bitbucket) |
| `get_service_health` | APM summary + active monitors | DataDog APM + Monitors API |
| `create_jira_incident` | Create a Bug issue in Jira | Jira REST API |
| `notify_oncall` | Send Slack notification | Slack API |
| `suggest_rollback` | Find last safe deployment | SCM API (merge history) |

## Code Mapping

| Component | Primary File | Key Class/Function |
|-----------|-------------|-------------------|
| Webhook handler | `src/ai_driven/api/webhooks/datadog.py` | `handle_datadog_webhook()` |
| DevOps agent | `src/ai_driven/agents/devops/agent.py` | `DevOpsAgent` |
| Incident model | `src/ai_driven/agents/devops/incident.py` | `IncidentReport` |
| DataDog client | `src/ai_driven/integrations/datadog_client.py` | `DataDogClient` |
| System prompt | `src/ai_driven/claude/prompts/devops_agent.py` | Triage instructions |
| Jira integration | `src/ai_driven/jira/client.py` | `JiraClient.create_issue()` |
| Slack notification | `slack_bot/notifications.py` | `SlackNotifier` |

## Claude API Usage

| Aspect | Value |
|--------|-------|
| Model | `claude-sonnet-4-6` |
| Extended thinking | OFF (speed is critical for incidents) |
| Max tool iterations | 50 (claude_max_tool_iterations) |
| Prompt caching | System prompt + tool schemas cached |
| Structured output | `IncidentReport` JSON via prompt instructions |
| Per-task cost ceiling | $3.00 USD (claude_max_cost_per_task_usd) — agent loop halts when cumulative cost crosses this. Primary runaway-loop defense. |

## Error Handling

| Failure | Recovery |
|---------|----------|
| DataDog API rate limit | Retry with backoff, proceed with partial data |
| DataDog API down | Create incident with available info, note "DataDog unreachable" |
| Jira API failure | Log error, still send Slack notification |
| Slack API failure | Log error, incident still created in Jira |
| Duplicate alert | Deduplicated in webhook handler (5 min window) |
| Unknown service | Create incident with raw alert data, no correlation |

## Metrics Emitted

- `ai.devops.triage.duration` — time from alert to incident created
- `ai.devops.incidents.created` — count by severity
- `ai.devops.rollback.suggested` — rollback suggestions count
- `ai.devops.correlation.found` — whether deployment correlation was found
