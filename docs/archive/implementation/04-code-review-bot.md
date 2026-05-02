# Sprint 4: AI Code Review Bot

**Status: COMPLETE**

## Goal

Make the AI Code Review Bot fully functional: PR webhook → fetch diff → analyze with Claude → post inline comments → post summary. Configurable review rules per team.

## Prerequisites

- Sprint 2 complete (SCM clients hardened, webhooks wired)
- At least one SCM (GitHub or Bitbucket) configured and working

---

## Tasks

### 4.1 Review Agent — Wire Real Tools

**File: `src/ai_driven/agents/reviewer/agent.py`**

- [x] Inject `SCMClient` via constructor
- [x] Wire `get_pr_diff` tool to real `SCMClient.get_pr_diff()`
- [x] Wire `read_file` tool to real `SCMClient.read_file()`
- [x] Wire `post_pr_comment` tool to real `SCMClient.post_pr_comment()`
- [x] Add tool: `list_repo_files` — for understanding project context
- [x] Add tool: `search_code` — find related code patterns
- [x] Add tool: `approve_pr` — approve PR if review passes
- [x] Add tool: `request_changes` — request changes if critical issues found
- [x] Truncate large diffs (>50KB) — summarize file by file, only include full patch for smaller files

### 4.2 Review Configuration Per Team

**New file: `src/ai_driven/agents/reviewer/config.py`**

- [x] Create `ReviewConfig` dataclass loaded from team settings JSON
- [x] Configurable options:
  - `auto_review_enabled: bool` — auto-review on PR open (default: True)
  - `auto_approve_enabled: bool` — auto-approve if no issues (default: False)
  - `review_on_update: bool` — re-review on PR push (default: True)
  - `ignore_paths: list[str]` — glob patterns to skip (e.g., `*.lock`, `docs/*`)
  - `severity_threshold: str` — only post comments above this severity (suggestion/warning/critical)
  - `custom_rules: list[str]` — additional review rules (e.g., "All SQL must use parameterized queries")
  - `max_comments: int` — max inline comments per review (default: 20)
- [x] Inject into review agent system prompt as `<team_rules>` XML block

### 4.3 Review Agent — Structured Output for Review Results

**New file: `src/ai_driven/agents/reviewer/models.py`**

- [x] Create `ReviewResult` model with `ReviewComment`
- [x] `parse_from_text` — parse JSON from Claude response (handles ```json wrapping)
- [x] `filter_by_severity` — filter comments by threshold
- [x] `filter_by_paths` — remove comments on ignored files
- [x] `format_summary` — format review summary for posting as PR comment

### 4.4 Review Agent — Comment Posting Strategy

**File: `src/ai_driven/agents/reviewer/agent.py`**

- [x] Post inline comments on specific files/lines for actionable issues
- [ ] Batch comments using GitHub's review API (submit as one review, not individual comments)
- [x] Post summary as a general comment with verdict, issue count, highlights, AI disclaimer
- [x] Add rate limiting — max N comments per PR (via ReviewConfig.max_comments)
- [x] Skip files matching `ignore_paths`

### 4.5 Review Agent — Extended Thinking for Complex Diffs

- [x] Use extended thinking (Opus) for PRs with >500 lines changed
- [x] Use standard model (Sonnet) for smaller PRs
- [x] Model selection based on diff size in `ReviewAgent._select_model()`
- [ ] Track model used and cost in `Review` record

### 4.6 Review Agent — Context Enhancement with RAG (Optional)

**Skipped for MVP** — basic diff-only review works without it.

### 4.7 Auto-Review Pipeline

**File: `src/ai_driven/api/webhooks/github.py`**

- [x] On PR opened/updated → check team's `auto_review_enabled` setting
- [x] Check `review_on_update` for synchronize events
- [x] If enabled → create Review task → enqueue to review queue
- [x] Skip if PR author is the AI bot itself (avoid self-review loop)
- [x] Skip if PR has label "skip-ai-review"

### 4.8 Review Response Handling

**File: `src/ai_driven/api/webhooks/github.py`**

- [x] Handle PR review comment replies (developer responds to AI comment)
- [x] If developer replies with `/ai-explain` → spawn a focused explanation task
- [x] If developer replies with `/ai-fix` → spawn a coding task for just that issue
- [x] Track conversation thread per review comment (comment_context with in_reply_to_id)

### 4.9 Tests — Review Bot

**New file: `tests/unit/test_review_agent.py`**

- [x] Test tool registration
- [x] Test system prompt contains review keywords and severity levels
- [x] Test `_build_initial_message` format
- [x] Test tool execution with mocked SCM client
- [x] Test error handling when SCM client is None

**New file: `tests/unit/test_review_config.py`**

- [x] Test default config
- [x] Test from_settings with empty/custom values
- [x] Test to_prompt_section with rules

**New file: `tests/unit/test_review_models.py`**

- [x] Test ReviewComment formatted_body
- [x] Test ReviewResult.from_dict parsing
- [x] Test ReviewResult.parse_from_text (JSON block, plain JSON, invalid fallback)
- [x] Test filter_by_severity (threshold filtering)
- [x] Test filter_by_paths (ignore patterns)
- [x] Test format_summary output

**New file: `tests/integration/test_review_flow.py`**

- [x] Test: Review agent with mocked Claude → tools called in sequence
- [x] Test: Review agent handles empty diff gracefully

### 4.10 Review Metrics

**File: `src/ai_driven/observability/metrics.py`**

- [x] Emit: `ai.review.duration` — total review time
- [x] Emit: `ai.review.diff_size` — lines changed in reviewed PR
- [x] Emit: `ai.review.model_used` — Opus vs Sonnet
- [x] Emit: `ai.review.verdict` — approve/request_changes/comment counts
- [ ] Store review record in PostgreSQL `reviews` table (wiring deferred to Sprint 5)

---

## Verification

```bash
# Unit tests
pytest tests/unit/test_review_agent.py tests/unit/test_review_config.py tests/unit/test_review_models.py -v

# Integration test
pytest tests/integration/test_review_flow.py -v

# All tests: 213 passing
pytest tests/ -v
```

## Files Created/Modified This Sprint

| Action | File |
|--------|------|
| Modify | `src/ai_driven/agents/reviewer/agent.py` |
| Create | `src/ai_driven/agents/reviewer/config.py` |
| Create | `src/ai_driven/agents/reviewer/models.py` |
| Modify | `src/ai_driven/claude/prompts/review_agent.py` |
| Modify | `src/ai_driven/api/webhooks/github.py` |
| Modify | `src/ai_driven/observability/metrics.py` |
| Create | `tests/unit/test_review_agent.py` |
| Create | `tests/unit/test_review_config.py` |
| Create | `tests/unit/test_review_models.py` |
| Create | `tests/integration/test_review_flow.py` |
