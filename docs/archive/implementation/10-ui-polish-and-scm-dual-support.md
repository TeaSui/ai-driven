# 10 — UI Polish, Task-Page Correctness, and SCM Dual-Support

**Status:** UI polish **landed and verified**. SCM dual-support **planned, not yet implemented** (next batch).
**Date opened:** 2026-04-24
**Related docs:** [09-feature-completeness-audit.md](09-feature-completeness-audit.md), [../review/feature-verification.md](../review/feature-verification.md)

## Context

Users reported a cluster of UX issues on the task-detail page that made real agent runs look broken even when the backend had successfully completed the work (PR opened, Jira transitioned, files written). Most of these issues traced to **a handful of concrete backend/frontend bugs** rather than design problems. This document records the bugs that were fixed in this pass (BUG-21 through BUG-29) and introduces the design for BitBucket Cloud support as a second SCM provider alongside GitHub.

## Part 1 — Bugs fixed (BUG-21 through BUG-29)

### BUG-21 — `input_data` truncated to 2 KB broke file rendering

**Symptom:** Task-detail page code pane showed "No files written yet" (or partial content) even for tasks that successfully wrote multi-KB files and opened PRs.

**Root cause:** [base.py:329](../../src/ai_driven/agents/base.py) emitted the `tool_call_start` event with `input_data = json.dumps(input_data)[:2000]`. For a `file.write` call with a 7 KB README, the stored JSON string was cut mid-value → frontend `JSON.parse` threw → `content` came out as empty string.

**Fix:** Cap at 64 KB instead of 2 KB. Same fix applied to `result_data` in `on_tool_result`. At 64 KB a single pathological event still can't explode the Redis list, but every normal code file round-trips intact.

**Verification:** New end-to-end task — 8,034-char `input_data` payload parses cleanly, 7,009-char README renders all lines in the UI.

### BUG-22 — `human_response` event not persisted to event log

**Symptom:** After answering an `human.ask_clarification` question, the yellow "Awaiting your input" card reappeared on page reload and blocked the agent visually, even though the backend had already resumed.

**Root cause:** [chat/router.py:446](../../src/ai_driven/api/chat/router.py) called `redis_client.publish(...)` only — pub/sub is ephemeral, live WebSocket subscribers receive it but the persisted `task:{id}:event_log` list never gets the entry. On reload the frontend fetches the event log via GET `/api/admin/tasks/{id}/events` and never sees the matching `human_response` → `extractPendingQuestion()` still returns the pending question forever.

**Fix:** Also `rpush` the event to `task:{id}:event_log` with a timestamp, so both live subscribers and reloading clients converge on the same truth.

**Verification:** Playwright `full-workflow.spec.ts` submits a real task, triggers `human_input_required`, POSTs to `/respond`, then asserts the persisted event log contains exactly one `human_response` with the matching answer.

### BUG-23 — "No files written yet" even when `file.write` events existed

**Symptom:** Related to BUG-21. Even with the truncation fix, the old tasks-page rendering logic had a fragile parser that returned `{}` when JSON.parse failed, losing both `path` and `content`.

**Fix:** Added a `salvage()` regex-based fallback in [tasks/[id]/page.tsx](../../ai-driven-web-ui/src/app/dashboard/tasks/%5Bid%5D/page.tsx) that recovers at least the `path` (and partial `content`) from truncated JSON strings. File tabs render for every `file.write` event, even if the task predates BUG-21's fix.

### BUG-24 — Reviews page hardcoded `balance.go` diff as demo fallback

**Symptom:** Users saw a fake Go `BalanceCache` diff on the Reviews page, no matter which real review they had open.

**Root cause:** [reviews/page.tsx](../../ai-driven-web-ui/src/app/dashboard/reviews/page.tsx) fell back to a `DIFF_LINES` constant (and matching `REVIEW_COMMENTS`) whenever no review was selected. Tabs/filters triggered this fallback frequently.

**Fix:** Removed `DIFF_LINES` and `REVIEW_COMMENTS` constants entirely. Replaced with a proper empty state: *"Select a review from the queue to see the PR diff and AI comments."* Header strings like `"PR #2419 — fix(ledger): guard BalanceCache.Get() with singleflight"` replaced with contextual values derived from the selected review.

### BUG-25 — File tabs on task-detail page weren't clickable

**Symptom:** Multi-file tasks showed all file tabs but clicking any tab except the latest did nothing. Users couldn't inspect earlier files.

**Root cause:** `FileTab` was rendered as a styled `<div>` with no `onClick` handler. The active tab was hardcoded to `i === edits.length - 1`.

**Fix:**
- `FileTab` now renders a semantic `<button>` with `onClick`, `aria-pressed`, and `aria-label="View <path>"`.
- `MainCodePane` holds `selectedPath` state; the code pane renders whichever file the user selected (defaults to latest).
- Deduplicates edits by path so a file written 3× shows as one tab, not three.

### BUG-26 — Code pane showed only partial file content from event stream

**Symptom:** Even with BUG-21 fixed, files >64 KB (or tasks from before the fix) rendered truncated content.

**Root cause:** The code pane read `content` from `extractWorkArtifacts(events)`, which can only ever be as complete as the event stream allows.

**Fix:** Stop relying on the event stream for file content. New backend endpoint:

```
GET /api/admin/tasks/{id}/file?path=<urlencoded path>
→ { path, content, size, repo, ref }
```

Implementation in [admin/router.py](../../src/ai_driven/api/admin/router.py): walks the event log to find the most recent `file.write` for the requested path, extracts `repo_full_name` + `branch`, then calls `GitHubClient.read_file(repo, path, ref=branch)`. Returns the **complete** file directly from GitHub regardless of event-stream fidelity.

Frontend uses SWR to call this endpoint whenever a tab is selected, with the event-stream content kept only as a fallback for offline/no-token cases.

### BUG-27 — Code pane showed the whole file, not a GitHub-style diff

**Symptom:** Users wanted GitHub-style red (removed) / green (added) line coloring for the changed files, not just the full current-state file.

**Fix:** New backend endpoint:

```
GET /api/admin/tasks/{id}/diff?path=<path>
→ { path, status, additions, deletions, patch, repo, pr_number }
```

Implementation: walks events to find the PR number + repo, calls `GitHubClient.get_pr_diff(repo, pr_number)`, returns the matching file's unified-diff `patch`.

Frontend renders it with a custom `parsePatch()` → `DiffRenderer` pipeline:
- Hunk headers (`@@ -a,b +c,d @@`) in blue
- Context lines neutral with paired old/new line numbers
- Additions: green background + green left bar + `+` marker
- Deletions: red background + red left bar + `-` marker

Falls back to `LiveFileContent` (full file, no coloring) when the task has no PR yet.

### BUG-28 — Thinking stream showed raw JSON instead of friendly titles

**Symptom:** Thinking stream cards looked like:
```
jira.get_issue ("{\"issue_key\": \"KAN-4\"}")
← "{\"key\": \"KAN-4\", \"summary\": \"Add input validation to the order
```
Hard to read and visually noisy.

**Fix:** New `ToolStepCard` component with:
- **Emoji icon** per domain (🎫 Jira, 🐙 GitHub, 🌿 git, 📁 repo, 📝 file, 🔍 code, 🧪 sandbox, 💬 human, ⚙️ other)
- **Human title** via `describeTool()` — "Read Jira ticket", "Write file", "Open pull request", "Move Jira ticket", "Run command", "Ask for clarification"
- **Subtitle** with just the meaningful args (`KAN-4`, file path, PR title)
- **Result preview** clipped to one line
- **Click to expand** → full raw tool name + input JSON + output JSON (still auditable)

### BUG-29 — `RealCodeContent` crashed on undefined content

**Symptom:** Unhandled runtime error `TypeError: Cannot read properties of undefined (reading 'split')` at `RealCodeContent`.

**Root cause:** When the live-file fetch returned `data.content === undefined` (e.g. binary file or read error), the component tried to `content.split('\n')` without a guard.

**Fix:** Loosened the prop type to `string | undefined | null` and added `(content ?? '')` before `.split`.

## Part 2 — Documentation on "how to answer the AI"

When an agent invokes `human.ask_clarification`, the UI now renders a **yellow card at the top of the task-detail page** containing:

1. **Label:** "Awaiting your input" (warn pill)
2. **Question** — in bold, full text from the tool input
3. **Context** — optional secondary line explaining why the question was asked
4. **Option buttons** — if the tool provided `options`, each appears as a brand-colored button; click to submit that exact text
5. **Free-text input** — `<input type="text">` labeled "Type your reply…"; press Enter or click **Send**

Both paths (button + text) call `POST /api/chat/tasks/{task_id}/respond` with `{answer: "..."}`. The card disappears as soon as the matching `human_response` event lands in the log.

Playwright coverage: 10 tests in [clarification.spec.ts](../../ai-driven-web-ui/e2e/clarification.spec.ts), 2 real-backend tests in [full-workflow.spec.ts](../../ai-driven-web-ui/e2e/full-workflow.spec.ts).

## Part 3 — SCM dual-support (GitHub + BitBucket Cloud) — LANDED

**Goal:** Keep the existing GitHub integration fully functional while adding BitBucket Cloud as a peer. Agents, tools, webhook receivers, and admin endpoints all pick the provider per-repo based on `Repository.scm_type`.

**Target test repo:** `https://bitbucket.org/origination1/ai-driven` (confirmed accessible, owner has app-password credentials).

**Status:** Plumbing landed and verified. The code paths below exist and are under test. End-to-end against the real private Bitbucket repo requires pasting credentials into `.env.local` (see Part 4).

### 3.1 SCM interface (abstraction layer)

Existing code already ships an abstraction: [src/ai_driven/scm/models.py](../../src/ai_driven/scm/models.py) exports `PRDiff`, `DiffFile`, `FileContent`, `PullRequest`, `PRComment`, `PRState`, `DirectoryEntry`. [src/ai_driven/scm/github_client.py](../../src/ai_driven/scm/github_client.py) is the first concrete implementation.

New work:

- **`src/ai_driven/scm/interface.py`** — formalize the interface as a `Protocol` / `ABC` with methods: `list_files`, `read_file`, `search_code`, `create_branch`, `write_file`, `create_pr`, `list_prs`, `get_pr`, `get_pr_diff`, `post_pr_comment`, `approve_pr`, `request_changes`, `merge_pr`, `close`.
- **`src/ai_driven/scm/bitbucket_client.py`** — BitBucket Cloud REST v2.0 implementation. Auth: HTTP Basic with `BITBUCKET_USERNAME` + `BITBUCKET_APP_PASSWORD`, or Workspace Access Tokens when available.
- **`src/ai_driven/scm/factory.py`** — `get_scm_client(repo: Repository) -> SCMClient`: picks `GitHubClient` vs `BitBucketClient` based on `repo.provider` (values: `"github"` | `"bitbucket"`).

### 3.2 BitBucket Cloud endpoint mapping

| Operation | GitHub | BitBucket Cloud |
|---|---|---|
| List files | `GET /repos/{owner}/{repo}/contents/{path}?ref={ref}` | `GET /2.0/repositories/{ws}/{repo}/src/{commit}/{path}/?pagelen=100` |
| Read file | Same as above (base64 content) | Same path, returns raw bytes |
| Create branch | `POST /repos/.../git/refs` | `POST /2.0/repositories/{ws}/{repo}/refs/branches` |
| Write file | `PUT /repos/.../contents/{path}` | `POST /2.0/repositories/{ws}/{repo}/src` with multipart form |
| Create PR | `POST /repos/.../pulls` | `POST /2.0/repositories/{ws}/{repo}/pullrequests` |
| PR diff (files) | `GET /repos/.../pulls/{n}/files` | `GET /2.0/repositories/{ws}/{repo}/pullrequests/{n}/diffstat` + `/diff` |
| Post PR comment | `POST /repos/.../pulls/{n}/comments` | `POST /2.0/repositories/{ws}/{repo}/pullrequests/{n}/comments` |
| Approve PR | `POST /repos/.../pulls/{n}/reviews` | `POST /2.0/repositories/{ws}/{repo}/pullrequests/{n}/approve` |
| Merge PR | `PUT /repos/.../pulls/{n}/merge` | `POST /2.0/repositories/{ws}/{repo}/pullrequests/{n}/merge` |

**Rate limits:** BitBucket Cloud = 1,000 requests/hour/user on Basic Auth. Same tenacity retry policy as GitHub client, with `429` + `5xx` retryable.

**Diff format:** BitBucket's `/pullrequests/{n}/diff` returns unified-diff text directly (no per-file patch wrappers). We'll post-process into the same `PRDiff` / `DiffFile[]` shape.

### 3.3 Config (new env vars)

In [src/ai_driven/config.py](../../src/ai_driven/config.py):

```python
bitbucket_username: str = ""          # BITBUCKET_USERNAME
bitbucket_app_password: str = ""      # BITBUCKET_APP_PASSWORD  (SecretStr in prod)
bitbucket_workspace: str = ""         # BITBUCKET_WORKSPACE     (e.g. "origination1")
bitbucket_webhook_secret: str = ""    # BITBUCKET_WEBHOOK_SECRET (optional, HMAC-SHA256)
```

### 3.4 Repository model

[src/ai_driven/db/postgres/models.py](../../src/ai_driven/db/postgres/models.py) `Repository`:

```python
provider: Mapped[str] = mapped_column(String(16), default="github", nullable=False)
# "github" | "bitbucket"
```

Migration: additive, default `"github"` for all existing rows, no data loss.

Admin UI "Add repository" flow gets a **provider radio** (GitHub / BitBucket Cloud). Different token-pasting fields shown per provider.

### 3.5 Tool naming

Tool names stay provider-scoped **today** (GitHub only) to avoid breaking prompt-cached system prompts. When BitBucket lands we add a second set of tools:

- `github.create_pr`, `github.get_pr_diff`, … (unchanged)
- `bitbucket.create_pr`, `bitbucket.get_pr_diff`, …

A `scm.*` alias layer over the factory can come later once both providers have stable tool sets. This avoids a big-bang rename.

### 3.6 Webhooks

Two receivers:

- `POST /api/webhooks/github` — HMAC-SHA256 verify `X-Hub-Signature-256` against `github_webhook_secret` (exists)
- `POST /api/webhooks/bitbucket` — verify `X-Hub-Signature` (HMAC-SHA256) against `bitbucket_webhook_secret`; event discriminator is `X-Event-Key` (e.g. `pullrequest:created`)

Both funnel into the same `WebhookEventStore` + `TaskProducer` downstream pipeline.

### 3.7 Admin endpoints

- `GET /api/admin/tasks/{id}/file?path=...` — today hardcodes `GitHubClient`. After refactor: `get_scm_client(task.repo)` → call `read_file`.
- `GET /api/admin/tasks/{id}/diff?path=...` — same pattern for `get_pr_diff`.

### 3.8 Test strategy

- **Unit:** `tests/scm/test_bitbucket_client.py` — mock httpx responses matching real BitBucket JSON shapes. Must cover pagination, 429 backoff, 404 NotFound, 401 Auth, the "file not found in PR" branch.
- **Integration:** A new Playwright spec `e2e/bitbucket-workflow.spec.ts` that stubs the `/file` and `/diff` endpoints with BitBucket-shaped responses (different `ref` naming, different diff patch structure) and asserts the UI renders the same.
- **Real-credential smoke:** `e2e/full-workflow.spec.ts` already submits a live task against `MinnMinn/skills-introduction-to-github`; a parallel test submits against `origination1/ai-driven` and asserts the same end-to-end flow works.

### 3.9 What landed this session

- **`Repository.scm_type` column** was already present as `String(20)` with values `"github" | "bitbucket"`. Verified in [db/postgres/models.py](../../src/ai_driven/db/postgres/models.py). No migration needed.
- **`BitbucketClient`** fully implemented in [scm/bitbucket_client.py](../../src/ai_driven/scm/bitbucket_client.py) (466 lines) — covers `list_files`, `read_file`, `search_code`, `create_or_update_file`, `create_branch`, `create_pull_request`, `get_pull_request`, `list_pull_requests`, `get_pr_diff`, `post_pr_comment`, `merge_pull_request`, `approve_pr`, `request_changes`, `get_commit_diff`, `list_recent_merges`. Auth: Basic (username + app password) or OAuth2 client-credentials via `from_oauth()`.
- **Factory `get_scm_client()`** in [scm/factory.py](../../src/ai_driven/scm/factory.py) was already picking between GitHub and Bitbucket based on an explicit `scm_type` + `credentials` dict.
- **NEW: `get_scm_client_from_settings(scm_type)`** — resolves credentials from runtime settings, so request-path code (admin endpoints, webhook handlers) no longer needs to thread credentials through. Raises `SCMError` with a clear message when required env vars are missing.
- **Admin endpoints refactored** — [`/api/admin/tasks/{id}/file`](../../src/ai_driven/api/admin/router.py) and [`/api/admin/tasks/{id}/diff`](../../src/ai_driven/api/admin/router.py) now:
  1. Resolve `scm_type` by joining `Task.repo_id → Repository.scm_type` (fallback `"github"` for legacy tasks with no repo row).
  2. Build the right client via the factory.
  3. Include `"provider"` in the response so the UI can show a badge.
  - Extracted helpers `_load_task_events`, `_resolve_task_scm_type`, `_extract_repo_and_ref_for_path`, `_extract_repo_and_pr` — smaller endpoints, shared logic.
- **PR-tool detection covers both providers** — `_extract_repo_and_pr` recognises `github.create_pr`, `bitbucket.create_pr`, `scm.create_pr`.
- **Frontend updates** ([tasks/[id]/page.tsx](../../ai-driven-web-ui/src/app/dashboard/tasks/%5Bid%5D/page.tsx)):
  - `PR_TOOLS` set includes `bitbucket.create_pr` + `bitbucket.create_pull_request`.
  - `describeTool()` maps both providers' PR tools to the same friendly titles.
  - PR-number extractor falls back to `result.id` (Bitbucket uses `id`, GitHub uses `number`).
  - `TaskFile` and `TaskDiff` TypeScript types include optional `provider?: 'github' | 'bitbucket'`.
- **Regression test** — new `bitbucket.create_pr events render identically to github` spec in [e2e/clarification.spec.ts](../../ai-driven-web-ui/e2e/clarification.spec.ts). Asserts the file-tab PR link renders `https://bitbucket.org/.../pull-requests/7` with label `PR #7` when the agent emits Bitbucket tool events. **11/11 clarification tests pass. 24/26 full suite (2 pre-existing skips).**
- **Backwards compatibility verified** — real GitHub smoke on task `5eaa05cb-…`: `/file` returns 7,009 chars of README, `/diff` returns PR #17 with 275 additions / 7 deletions. `provider=github` echoed in responses.

### 3.10 Still to land for real Bitbucket use

- **Paste real Bitbucket credentials** into `.env.local`:
  ```
  AI_DRIVEN_BITBUCKET_USERNAME=your-bitbucket-username
  AI_DRIVEN_BITBUCKET_APP_PASSWORD=ATBB...   # app password with repository:write, pullrequest:write
  AI_DRIVEN_BITBUCKET_WORKSPACE=origination1
  ```
- **Register the repo** via `POST /api/admin/repos` with `scm_type: "bitbucket"`, `full_name: "origination1/ai-driven"`. Admin UI "Add repository" dropdown already supports both providers.
- **Submit a coding task** with `context.repo_full_name = "origination1/ai-driven"` — the same flow that works on `MinnMinn/skills-introduction-to-github` today should complete end-to-end once creds are in place.
- **Webhook receiver** at [`/api/webhooks/bitbucket`](../../src/ai_driven/api/webhooks/bitbucket.py) already exists; `AI_DRIVEN_BITBUCKET_WEBHOOK_SECRET` gates signature verification.

### 3.11 Rollout sequence (now historical)

1. Add `provider` column to `Repository` model + migration. Default existing rows to `"github"`.
2. Write the SCM `Protocol` and move the existing `GitHubClient` method signatures onto it.
3. Implement `BitBucketClient` with full method coverage + unit tests.
4. Add `get_scm_client()` factory.
5. Migrate admin endpoints (`/file`, `/diff`) and agent tool handlers to use the factory.
6. Add BitBucket tool names (`bitbucket.*`) with handlers that delegate to the client.
7. Wire up the webhook receiver.
8. Update Admin UI repo-add flow + sidebar to show provider per repo.
9. Smoke-test end-to-end against `origination1/ai-driven`.
10. Document the final state in this file and in [configuration.md](../configuration.md).

No existing GitHub code path changes semantically — all current tasks keep working throughout the rollout.

## Test coverage delivered this session

| Spec | Tests | Status |
|---|---|---|
| `e2e/clarification.spec.ts` | 10 | ✅ all pass |
| `e2e/full-workflow.spec.ts` (real backend) | 2 | ✅ both pass |
| `e2e/tasks.spec.ts` | 3 | ✅ all pass (fixed stale `{data: …}` wrappers) |
| `e2e/reviews.spec.ts` | 3 | ✅ all pass (fixed stale tab-label strings) |
| `e2e/dashboard.spec.ts` | 3 | ✅ all pass |
| `e2e/auth.spec.ts` | 4 (2 skipped) | 2 pass, 2 skipped with TODO — middleware not implemented |

**Total: 25 tests, 23 pass, 2 skipped (pre-existing auth middleware gap, unrelated to this pass).**

## Sprint 10 — consolidated checklist

### Part 1 — UI bugs (all landed)
- [x] BUG-21 — `input_data` 2 KB truncation → 64 KB cap
- [x] BUG-22 — `human_response` persisted to event log
- [x] BUG-23 — `salvage()` regex fallback for truncated JSON payloads
- [x] BUG-24 — hardcoded `balance.go` demo removed from Reviews page
- [x] BUG-25 — File tabs clickable (`<button>` + `selectedPath` state)
- [x] BUG-26 — `GET /api/admin/tasks/{id}/file` for live file content
- [x] BUG-27 — `GET /api/admin/tasks/{id}/diff` for GitHub-style red/green diff
- [x] BUG-28 — `ToolStepCard` with emoji icons + friendly tool titles
- [x] BUG-29 — `RealCodeContent` nullish fallback for undefined content

### Part 2 — BitBucket dual-support (all landed)
- [x] `SCMClient` Protocol with provider-agnostic methods
- [x] `BitbucketClient` implementation (Cloud REST v2.0)
- [x] `get_scm_client(scm_type, credentials)` factory
- [x] `get_scm_client_from_settings(scm_type)` factory (for request-path helpers)
- [x] `Repository.scm_type` field for per-repo provider selection
- [x] Admin `/file` and `/diff` endpoints route by provider via `Repository.scm_type`
- [x] Frontend `PR_TOOLS` recognises `bitbucket.create_pr` + `bitbucket.create_pull_request`
- [x] `describeTool()` renders friendly titles for `bitbucket.*` tool names
- [x] Coding agent registers `bitbucket.create_pr` alongside `github.create_pr`
- [x] Review agent registers `bitbucket.get_pr_diff`, `bitbucket.post_pr_comment`, `bitbucket.approve_pr`, `bitbucket.request_changes`
- [x] BitBucket webhook receiver at `POST /webhooks/bitbucket` (pullrequest:created / updated / comment_created)
- [x] Config: `AI_DRIVEN_BITBUCKET_USERNAME`, `AI_DRIVEN_BITBUCKET_APP_PASSWORD`, `AI_DRIVEN_BITBUCKET_WORKSPACE`
- [x] `scripts/bitbucket_happy_case.py` smoke script (factory + list + PR metadata)
- [x] Smoke-test end-to-end against `origination1/ai-driven` — `scripts/bitbucket_happy_case.py` PASSED (2026-04-25): factory + list_files(main) + read_file(README.md, 2610 bytes) + list_pull_requests(OPEN)=0. Username value that worked: personal email (`nguyenminhtung.near@gmail.com`) — not the workspace slug. Diff/reviewers/checks paths deferred until an open PR exists on the repo.
- [ ] Admin UI repo-add flow surfaces the provider dropdown (`POST /api/admin/repos` already accepts `scmType`)

### Part 3 — Review sidebar real data (new, landed)
See [11-code-review-sidebar.md](11-code-review-sidebar.md). `PRReviewer` + `PRCheck` models, `get_pr_reviewers` + `get_pr_checks` on both SCM clients, `GET /api/admin/reviews/{id}/reviewers` + `/checks` endpoints, and `ReviewSidebar` rewritten to call them via SWR. The hardcoded Mira Delgado / Jamie Ortiz / ci-unit rows are gone.
