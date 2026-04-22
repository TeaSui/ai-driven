# Database ER Diagram

All 8 tables in PostgreSQL + pgvector. Shows columns, types, primary/foreign keys, and relationships.

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
    conversation_records {
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

    teams ||--o{ repositories : "owns"
    teams ||--o{ tasks : "owns"
    teams ||--o{ incidents : "owns"
    repositories ||--o{ tasks : "targets"
    repositories ||--o{ code_embeddings : "indexed"
    tasks ||--o{ reviews : "produces"
    tasks ||--o{ conversation_records : "logs"
    tasks ||--o{ task_evaluations : "graded"
```

## Table Descriptions

| Table | Purpose |
|-------|---------|
| **teams** | Multi-tenant org. Holds API key, Claude provider preference, concurrency limits |
| **repositories** | GitHub/Bitbucket repos registered for agent access |
| **tasks** | Central work unit. Tracks agent type, status, tokens, cost, iterations |
| **reviews** | Code review results with inline comments (file + line + severity) |
| **incidents** | DataDog alerts triaged by DevOps agent with RCA |
| **conversation_records** | Full Claude message history per task for audit/replay |
| **code_embeddings** | pgvector-indexed code chunks for RAG semantic search |
| **task_evaluations** | Per-dimension grading scores from evaluation system |

## Status Flow (tasks.status)

```
queued → planning → coding → testing → pr_created → completed
                                    ↘ failed
                         ↘ review_failed
                                          ↘ deploy_failed
```
