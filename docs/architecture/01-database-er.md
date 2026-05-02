# Database ER Diagram

All 11 base tables in PostgreSQL + pgvector (excluding `alembic_version`).
Shows columns, types, primary/foreign keys, and relationships.

```mermaid
erDiagram
    teams {
        uuid id PK
        string name UK
        string api_key_hash
        string claude_provider
        json settings_json
        boolean is_active
        integer max_concurrent_tasks
        datetime created_at
        datetime updated_at
    }
    team_members {
        uuid id PK
        uuid team_id FK
        string email
        string role
        string api_key_hash UK
        boolean is_active
        datetime created_at
    }
    repositories {
        uuid id PK
        uuid team_id FK
        string scm_type
        string full_name
        string clone_url
        string default_branch
        boolean is_active
        json settings_json
        datetime created_at
    }
    tasks {
        uuid id PK
        uuid team_id FK
        uuid repo_id FK
        string jira_key
        string agent_type
        string orchestration_tier
        string status
        string title
        json input_data
        json output_data
        text error_message
        integer total_tokens
        float total_cost_usd
        integer iterations
        datetime created_at
        datetime started_at
        datetime completed_at
        datetime last_heartbeat_at
    }
    reviews {
        uuid id PK
        uuid task_id FK
        string pr_url
        integer pr_number
        string repo_full_name
        string status
        text summary
        json comments_json
        integer issues_found
        integer critical_issues
        datetime created_at
    }
    incidents {
        uuid id PK
        uuid team_id FK
        string title
        string severity
        string status
        string service
        string metric
        string current_value
        string threshold
        json rca_json
        datetime started_at
        datetime resolved_at
        datetime created_at
    }
    conversations {
        uuid id PK
        uuid task_id FK
        json messages_json
        integer total_input_tokens
        integer total_output_tokens
        float total_cost_usd
        string model_used
        datetime created_at
        datetime updated_at
    }
    code_embeddings {
        uuid id PK
        uuid repo_id FK
        string file_path
        text chunk_content
        integer chunk_index
        vector embedding
        json metadata_json
        string last_commit_sha
    }
    task_evaluations {
        uuid id PK
        uuid task_id FK
        string dimension
        float score
        text reasoning
        json strengths
        json weaknesses
        string grader_model
        datetime created_at
    }
    task_artifacts {
        uuid id PK
        uuid task_id FK
        string role
        jsonb payload
        string verdict
        text reasoning
        string model_used
        integer input_tokens
        integer output_tokens
        numeric cost_usd
        datetime created_at
    }
    task_override_audit {
        uuid id PK
        uuid task_id FK
        uuid overridden_by_member_id FK
        text from_status
        text to_status
        text action
        text reason
        datetime created_at
    }

    teams ||--o{ team_members : "has"
    teams ||--o{ repositories : "owns"
    teams ||--o{ tasks : "owns"
    teams ||--o{ incidents : "owns"
    repositories ||--o{ tasks : "targets"
    repositories ||--o{ code_embeddings : "indexed"
    tasks ||--o{ reviews : "produces"
    tasks ||--o{ conversations : "logs"
    tasks ||--o{ task_evaluations : "graded"
    tasks ||--o{ task_artifacts : "specialist verdicts"
    tasks ||--o{ task_override_audit : "overrides"
    team_members ||--o{ task_override_audit : "author"
```

## Table Descriptions

| Table | Purpose |
|-------|---------|
| **teams** | Multi-tenant org. Holds team-level API key, Claude provider preference, concurrency limits |
| **team_members** | RBAC members per team. Role ∈ {owner, admin, member, viewer}. Optional per-member API key hash |
| **repositories** | GitHub/Bitbucket repos registered for agent access |
| **tasks** | Central work unit. Tracks agent type, orchestration tier, status, tokens, cost, iterations, heartbeat |
| **reviews** | Code review results with inline comments (file + line + severity) |
| **incidents** | DataDog alerts triaged by DevOps agent with RCA |
| **conversations** | Full Claude message history per task for audit/replay |
| **code_embeddings** | pgvector-indexed code chunks for RAG semantic search |
| **task_evaluations** | Per-dimension grading scores from evaluation system |
| **task_artifacts** | One row per (task_id, role) — specialist verdict, reasoning, payload, tokens, cost (NUMERIC micro-dollar). Enforced UNIQUE(task_id, role) |
| **task_override_audit** | Audit row per admin override or reaper auto-fail. Captures from_status → to_status, action ∈ {rerun_coding, rerun_qa, force_pass, reap}, and reason |

## Status Flow (tasks.status)

```
queued → running → (orchestrator) → coding → awaiting_qa → completed
                         ↓             ↓         ↓
                      blocked       failed    qa_failed
```

`blocked` — specialist verdict=block in live mode (pre-coding halt).
`awaiting_qa` — PR opened, QA specialist enqueued, verdict pending.
`qa_failed` — post-coding QA verdict=block. Terminal unless admin-overridden.
`cancelled` — user-initiated stop (not counted as failure).

Admin overrides can transition `blocked → coding`, `qa_failed → awaiting_qa`,
or `qa_failed → completed`; reapers write `*_stuck_reaped` into
`task_override_audit.reason` when a worker tier is stuck > 30 min.
