# Workflow Agent — End-to-End Pipeline

Full Jira-to-merge scenario: Jira webhook triggers Workflow Agent, which orchestrates Coding Agent → Review Agent → merge → notify.

```mermaid
sequenceDiagram
    autonumber
    participant Jira as Jira Webhook
    participant API as FastAPI
    participant WF as Workflow Agent
    participant CA as Coding Agent
    participant Claude as Claude API
    participant GH as GitHub
    participant RA as Review Agent
    participant Slack as Slack API
    participant PG as PostgreSQL

    Note over Jira,PG: Scenario: Jira Ticket → Code → Review → Merge → Notify

    Jira->>API: POST /webhooks/jira/issue
    Note over API: Issue transitioned to "Ready"
    API->>PG: INSERT task (workflow agent)
    API->>WF: Dispatch workflow

    WF->>Claude: Plan orchestration steps
    Claude-->>WF: 1. Code  2. Review  3. Merge  4. Notify

    rect rgb(232, 245, 233)
        Note over WF,GH: Phase 1: Coding
        WF->>CA: dispatch_coding_agent(jiraKey, repo)
        CA->>Claude: Fetch Jira + explore repo
        Claude-->>CA: tool_use: get_jira_issue
        CA->>CA: Read files, search code
        CA->>Claude: Write implementation
        Claude-->>CA: tool_use: write_file (x N)
        CA->>CA: Run tests in sandbox
        CA->>GH: create_pull_request
        GH-->>CA: PR #2418 created
        CA->>Jira: add_jira_comment("PR opened")
        CA-->>WF: {success: true, pr_number: 2418}
    end

    WF->>WF: check_task_status (poll)

    rect rgb(227, 242, 253)
        Note over WF,GH: Phase 2: Review
        WF->>RA: dispatch_review_agent(repo, pr: 2418)
        RA->>GH: get_pr_diff
        GH-->>RA: Diff (+142 -38, 7 files)
        RA->>Claude: Analyze diff for issues
        Claude-->>RA: 3 comments, no blockers
        RA->>GH: post_pr_comment (inline x3)
        RA->>GH: approve_pr
        RA-->>WF: {approved: true}
    end

    rect rgb(243, 229, 245)
        Note over WF,GH: Phase 3: Merge + Notify
        WF->>GH: merge_pull_request(squash)
        GH-->>WF: Merged
        WF->>Jira: Update status → "Done"
        WF->>Slack: notify_team("PR #2418 merged")
        Slack-->>WF: Message sent
    end

    WF->>PG: UPDATE task completed
    Note over WF,PG: Full cycle: Jira → Code → Review → Merge → Notify
```

## Workflow Agent Tools

| Tool | Purpose |
|------|---------|
| `dispatch_coding_agent` | Enqueue coding task, returns task_id |
| `dispatch_review_agent` | Enqueue review task for a PR |
| `check_task_status` | Poll task state via DynamoDB TaskStateMachineStore |
| `merge_pull_request` | Merge via GitHub/Bitbucket (squash/merge/rebase) |
| `notify_team` | Send Slack message to channel (supports channel override) |
| `mcp_*` | Additional MCP tools (GitHub, Jira, Slack) registered at startup if enabled |

## Orchestration Pattern

```
1. Parse input (Jira key, repo, branch)
2. Dispatch CodingAgent → poll until completed
3. Dispatch ReviewAgent → poll until approved
4. Merge PR (squash by default)
5. Update Jira status → "Done"
6. Notify team via Slack
7. Mark workflow task as completed
```

## Error Handling

- If CodingAgent fails → workflow fails, Jira comment posted with error
- If ReviewAgent requests changes → CodingAgent re-dispatched with feedback
- If merge conflicts → workflow reports blocked status
- Max polling attempts: 60 (with 10s intervals = 10min timeout per sub-task)
