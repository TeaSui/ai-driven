# Task State Machine

All states across the 5 pipeline stages (Jira → Code → Review → Deploy → Monitor) with transitions and error paths.

```mermaid
stateDiagram-v2
    [*] --> Queued: POST /api/chat/send

    state "Jira Stage" as JiraStage {
        Queued --> Planning: Agent picks up task
        Planning --> Planning: Fetch Jira ticket\nParse acceptance criteria
    }

    state "Code Stage" as CodeStage {
        Planning --> Coding: Start implementation
        Coding --> Coding: Read files\nSearch code\nRAG semantic search
        Coding --> Writing: Plan complete
        Writing --> Writing: Write files\nCommit changes
        Writing --> Testing: Code written
        Testing --> Testing: Run build\nRun tests in sandbox
        Testing --> Writing: Tests fail\n(retry)
    }

    state "Review Stage" as ReviewStage {
        Testing --> PR_Created: Tests pass\nCreate PR
        PR_Created --> Under_Review: ReviewAgent dispatched
        Under_Review --> Under_Review: Read diff\nPost inline comments
        Under_Review --> Changes_Requested: Issues found
        Changes_Requested --> Writing: Fix requested changes
        Under_Review --> Approved: No issues
    }

    state "Deploy Stage" as DeployStage {
        Approved --> Merging: Merge PR
        Merging --> Deployed: PR merged
    }

    state "Monitor Stage" as MonitorStage {
        Deployed --> Monitoring: Post-deploy check
        Monitoring --> Completed: All healthy
    }

    Completed --> [*]

    state "Error Paths" as ErrorPaths {
        Coding --> Failed: Max iterations exceeded
        Writing --> Failed: Write permission denied
        Testing --> Failed: Tests fail after retries
        PR_Created --> Review_Failed: Review rejected
        Merging --> Deploy_Failed: Merge conflict
    }

    state "User-initiated" as UserCancel {
        Coding --> Cancelled: User clicks Stop
        Writing --> Cancelled: User clicks Stop
        Testing --> Cancelled: User clicks Stop
        Under_Review --> Cancelled: User clicks Stop
    }

    Failed --> [*]
    Cancelled --> [*]
    Review_Failed --> [*]
    Deploy_Failed --> [*]
```

> **`cancelled` vs `failed`**: a user-initiated stop is **not** a failure. Keeping them separate avoids polluting `successRate` metrics with deliberate user cancels.

## Pipeline Stage ↔ Task Status Mapping

| Task Status | Pipeline Stage | Stage Status | UI Shows | Rule |
|-------------|---------------|--------------|----------|------|
| `queued` | Code | queued | Coding (pulsing) | |
| `planning` | Code | active | Coding (pulsing) | |
| `running` | Code | active | Coding (pulsing) | |
| `coding` | Code | active | Coding (pulsing) | |
| `testing` | Review | active | In Review | |
| `pr_created` | Review | active | In Review | |
| `review_failed` | Review | failed | Blocked | |
| `deploy_failed` | Deploy | failed | Blocked | |
| `completed` | Monitor | passed | **Merged** | Human assignee reviewed and merged the PR |
| `completed` | Monitor | passed | **Deployed** | AI agent completed the full pipeline autonomously |
| `cancelled` | _(terminal)_ | cancelled | **Stopped by user** | User-initiated stop; excluded from successRate KPI |
| `failed` | Monitor | failed | Blocked | |

## Backend Mapping (router.py)

```python
_STATUS_TO_STAGE = {
    "queued": "code",
    "planning": "code",
    "running": "code",
    "coding": "code",
    "testing": "review",
    "pr_created": "review",
    "review_failed": "review",
    "deploy_failed": "deploy",
    "completed": "monitor",
    "cancelled": "monitor",
    "failed": "monitor",
}

_STATUS_TO_STAGE_STATUS = {
    "queued": "queued",
    "planning": "active",
    "running": "active",
    "coding": "active",
    "testing": "active",
    "pr_created": "active",
    "review_failed": "failed",
    "deploy_failed": "failed",
    "completed": "passed",
    "cancelled": "cancelled",
    "failed": "failed",
}
```
