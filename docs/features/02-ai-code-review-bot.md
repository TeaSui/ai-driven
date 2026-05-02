# Feature 2: AI Code Review Bot

## Purpose

Automatically review every pull request for security issues, bugs, performance problems, and style violations. Posts inline comments on the exact lines that need attention, plus a summary verdict. Reduces review bottleneck and catches issues before human reviewers see the PR.

## Who Uses It

- **Developers** — receive AI review feedback on their PRs within minutes
- **Reviewers** — see pre-flagged issues, can focus on architecture/design instead of catching typos
- **Team Leads** — configure review rules per team (severity threshold, ignored paths, custom rules)

## Expected Result

Given a PR with 3 changed files, the bot:
- Posts 2-5 inline comments on specific lines (security warning, bug risk, etc.)
- Posts a summary comment: "Found 1 critical, 2 warnings. Requesting changes."
- Sets PR status: "Changes Requested" (or "Approved" if clean)
- Completes in under 2 minutes for typical PRs

---

## Full Workflow (Step by Step)

### Step 1: Trigger

**PR webhook fires when a pull request is opened or updated.**

| SCM | Event | Handler |
|-----|-------|---------|
| GitHub | `pull_request` (opened, synchronize) | `api/webhooks/github.py` |
| Bitbucket | `pullrequest:created`, `pullrequest:updated` | `api/webhooks/bitbucket.py` |
| Manual | `/ai-review` comment on PR | `api/webhooks/github.py` → `_handle_issue_comment()` |

**What happens:**
1. Validate webhook signature (HMAC for GitHub, IP allowlist for Bitbucket)
2. **Self-review skip**: if PR author is the bot account OR PR has label `skip-ai-review` → ignore
3. Deduplicate: check `WebhookEventStore` to avoid reviewing the same commit twice
4. Look up repository in PostgreSQL → get `team_id`
5. Load team's `ReviewConfig` (auto_review_enabled, severity_threshold, ignore_paths, custom_rules)
6. If `auto_review_enabled` is false → ignore (unless manually triggered via `/ai-review`)
7. Create `Task` record (status: `QUEUED`, agent_type: `review`)
8. Publish SQS message

### Step 2: Worker Picks Up Task

Same as Coding Agent — ECS worker polls SQS, updates status to `RUNNING`.

### Step 3: Context Gathering (Tool Calls)

**File: `backend/src/ai_driven/agents/reviewer/agent.py`**

The review agent gathers the PR diff and surrounding context:

```
Claude API Call: Review
├── Model: claude-sonnet-4-6 (all slots pinned to Sonnet)
├── Extended thinking: ON for large diffs, OFF for small
├── System prompt: review guidelines + team custom rules
├── Tools: get_pr_diff, read_file, list_repo_files, search_code, post_pr_comment
└── Multi-turn loop
```

**Tool calls the agent typically makes:**

1. `get_pr_diff(repo="org/repo", pr_number=42)` → structured diff with file patches
   - If total diff > 50KB: each file patch truncated to 2KB
2. `read_file(repo="org/repo", path="src/api/users.py", ref="feature-branch")` → full file for context
3. `search_code(repo="org/repo", query="def validate_email")` → find related code
4. `list_repo_files(repo="org/repo", path="src/api/")` → understand module structure

### Step 4: AI Review Analysis

Claude analyzes the diff with these review categories:

| Category | What It Checks |
|----------|---------------|
| **Security** | OWASP top 10: injection, XSS, auth bypass, secrets in code |
| **Bugs** | Null references, off-by-one, race conditions, logic errors |
| **Performance** | N+1 queries, unnecessary allocations, missing indexes |
| **Style** | Naming conventions, dead code, complexity |
| **Improvements** | Better patterns, simpler approaches, missing tests |

**Severity levels:** `critical` > `warning` > `suggestion` > `nitpick`

Claude produces a structured JSON response:
```json
{
  "verdict": "changes_requested",
  "comments": [
    {
      "path": "src/api/users.py",
      "line": 42,
      "severity": "critical",
      "category": "security",
      "body": "SQL injection risk: user input is interpolated directly into query. Use parameterized queries."
    }
  ],
  "summary": "Found 1 critical security issue..."
}
```

### Step 5: Post Comments

**The agent posts results back to the PR:**

1. **Filter by team config:**
   - Remove comments below `severity_threshold` (e.g., skip nitpicks)
   - Remove comments on `ignore_paths` (e.g., `*.generated.ts`, `vendor/`)
2. **Post inline comments** via `post_pr_comment(repo, pr_number, path, line, body)`
   - Each comment includes severity emoji: `[CRITICAL]`, `[WARNING]`, `[SUGGESTION]`
3. **Post summary comment** with:
   - Severity breakdown (1 critical, 2 warnings)
   - Positive highlights (what the PR does well)
   - Verdict: Approve / Changes Requested
4. **Set PR status** (if configured): approve or request changes via SCM API

### Step 6: Completion

1. Task status → `COMPLETED`
2. Review record saved to PostgreSQL (summary, comment count, verdict)
3. Metrics emitted to DataDog
4. If part of workflow: triggers `on_review_completed` in WorkflowTriggers

---

## Configuration (Per Team)

```python
ReviewConfig:
  auto_review_enabled: bool = True        # Review PRs automatically
  review_on_update: bool = True           # Re-review when PR is updated
  severity_threshold: str = "suggestion"  # Minimum severity to post
  max_comments: int = 20                  # Cap inline comments per review
  ignore_paths: list[str] = []            # Glob patterns: ["*.lock", "vendor/*"]
  custom_rules: list[str] = []            # Team-specific rules injected into prompt
```

Stored in `teams.settings_json` under the `"review"` key.

## Code Mapping

| Step | Primary File | Key Class/Function |
|------|-------------|-------------------|
| Trigger (GitHub) | `backend/src/ai_driven/api/webhooks/github.py` | `_handle_pr_event()` |
| Trigger (Bitbucket) | `backend/src/ai_driven/api/webhooks/bitbucket.py` | `handle_bitbucket_webhook()` |
| Self-review skip | `backend/src/ai_driven/api/webhooks/github.py` | Bot author + label check |
| Review config | `backend/src/ai_driven/agents/reviewer/config.py` | `ReviewConfig.from_settings()` |
| Agent loop | `backend/src/ai_driven/agents/reviewer/agent.py` | `ReviewAgent.run()` |
| Model selection | `backend/src/ai_driven/agents/reviewer/agent.py` | `ReviewAgent._select_model()` |
| Diff truncation | `backend/src/ai_driven/agents/reviewer/agent.py` | `_truncate_diff_files()` |
| Result parsing | `backend/src/ai_driven/agents/reviewer/models.py` | `ReviewResult.parse_from_text()` |
| Severity filtering | `backend/src/ai_driven/agents/reviewer/models.py` | `filter_by_severity()` |
| Path filtering | `backend/src/ai_driven/agents/reviewer/models.py` | `filter_by_paths()` |
| Summary formatting | `backend/src/ai_driven/agents/reviewer/models.py` | `format_summary()` |
| Comment posting | `backend/src/ai_driven/scm/github_client.py` | `post_pr_comment()` |

## Claude API Usage

| Aspect | Value |
|--------|-------|
| Model (small diff <500 lines) | `claude-sonnet-4-6 (all slots pinned to Sonnet)` |
| Model (large diff >500 lines) | `claude-sonnet-4-6 (all slots pinned to Sonnet)` with extended thinking |
| Prompt caching | System prompt + review guidelines cached (`cache_control: ephemeral`) |
| Structured output | JSON via `stop_sequences=["```"]` — stops after JSON code fence |
| Few-shot examples | `<examples>` section with sample review (1 critical + 1 suggestion + approve verdict) |
| Team rules injection | Appended to system prompt via `ReviewConfig.to_prompt_section()` |
| Per-task cost ceiling | $3.00 USD (claude_max_cost_per_task_usd) — agent loop halts when cumulative cost crosses this. Primary runaway-loop defense. |

### Local Repository Access

When the CodingAgent clones a repository, it stores the clone path in Redis (`repo_clone:{repo_full_name}`). The ReviewAgent checks this key first:

1. `read_file` tool checks Redis for local clone path
2. If a local clone exists on disk, reads from filesystem (faster, no API call)
3. Falls back to SCM API (GitHub/Bitbucket) if no local clone

This enables the Review tab in Chat to read from the same repo the Coding agent cloned, avoiding redundant API calls.

**Constructor:** `ReviewAgent(local_repo_path="/path/to/clone")` for explicit injection, or auto-discovered via Redis.

## Error Handling

| Failure | Recovery |
|---------|----------|
| PR diff too large (>50KB) | Truncate each file patch to 2KB with `[truncated]` marker |
| SCM API rate limit | Retry with exponential backoff (tenacity, 3 retries) |
| Claude API failure | Circuit breaker → fallback model |
| Duplicate webhook | Deduplicated via `WebhookEventStore` |
| Bot's own PR | Self-review skip (bot author check) |
| Comment posting fails | Log error, continue with remaining comments |

## Metrics Emitted

- `ai.review.duration` — time from start to all comments posted
- `ai.review.diff_size` — lines changed in the PR
- `ai.review.comments_posted` — number of inline comments
- `ai.review.issues_found` — issues by severity
- `ai.review.verdict` — approve/changes_requested/comment_only
- `ai.review.model_used` — which Claude model was selected
