# 12 — SCM via MCP: Official GitHub MCP now, Bitbucket when it lands

**Status:** planned (not started)
**Date opened:** 2026-04-24
**Related docs:**
- [10-ui-polish-and-scm-dual-support.md](10-ui-polish-and-scm-dual-support.md) — current `SCMClient` Protocol design
- [11-code-review-sidebar.md](11-code-review-sidebar.md) — consumers of `get_pr_reviewers` / `get_pr_checks` / `get_pr_commits`
- [../mcp.md](../mcp.md) — dev-tool MCP (not this one)

## Context

The current SCM layer is a hand-rolled `SCMClient` Protocol with two implementations:

| Layer | GitHub | Bitbucket |
|---|---|---|
| Protocol | `src/ai_driven/scm/interface.py` — 15 async methods | same |
| Implementation | `src/ai_driven/scm/github_client.py` (~600 lines, `httpx` + retry + tenacity) | `src/ai_driven/scm/bitbucket_client.py` (~500 lines) |
| Agent tools | `github.create_pr`, `github.get_pr_diff`, `github.approve_pr`, `github.request_changes`, `github.post_pr_comment`, `github.get_pr_reviewers`, `github.get_pr_checks`, `github.get_pr_commits`, plus provider-agnostic `repo.*`, `file.*`, `git.*` | same shape with `bitbucket.*` prefix |

GitHub ships an official MCP server ([`github/github-mcp-server`](https://github.com/github/github-mcp-server)) that exposes **102 tools** over stdio, fully maintained by GitHub. Bitbucket does **not** ship an official MCP server as of April 2026. Third-party Bitbucket MCPs exist but are lightly maintained and don't cover Bitbucket Cloud + Server parity.

### Note: we are also swapping off a deprecated community server

Our current MCP registration at [src/ai_driven/mcp/config.py:25](../../src/ai_driven/mcp/config.py#L25) points at `@modelcontextprotocol/server-github` — the older community/reference implementation from the `modelcontextprotocol/servers` repo. That package is **not** GitHub-official, and its upstream README now redirects users to `github/github-mcp-server`. So Phase A is two changes in one:

1. Swap `@modelcontextprotocol/server-github` → `github/github-mcp-server` (official, maintained by GitHub) in `mcp/config.py`.
2. Introduce `MCPSCMAdapter` so the whole `SCMClient` surface is served by that subprocess instead of by our hand-rolled `GitHubClient`.

Both changes ship together behind the `AI_DRIVEN_SCM_GITHUB_VIA_MCP` flag. When the flag is off, neither change takes effect — the community server is still used for the existing tool-bridge path and `GitHubClient` still serves the SCM protocol. When the flag is on, the backend uses only the official server.

The user's question was: *"Should we use MCP for both providers, with one common system that differs only at the MCP-call step?"*

**Answer:** the common-system abstraction already exists (`SCMClient` Protocol). Moving GitHub to the official MCP server is a code-deletion win (~600 lines → thin adapter). Moving Bitbucket to MCP is not a win **today** because no maintained server exists — we'd replace our `BitbucketClient` with something we'd have to maintain anyway, plus process overhead. **Swap Bitbucket once an official or well-maintained MCP server ships.**

## Decision

Adopt MCP provider-by-provider, **not both at once**, on a trigger schedule:

1. **Now — Phase A:** Replace `GitHubClient` with the official `github-mcp-server`, behind an `MCPSCMAdapter` that implements the existing `SCMClient` Protocol. Keep `BitbucketClient` as-is. Every existing call site in the codebase continues to work unchanged because the contract is the Protocol, not the concrete class.
2. **When Bitbucket MCP matures — Phase B:** Swap `BitbucketClient` for a `MCPSCMAdapter` wired to the Bitbucket MCP server. Same Protocol contract, same call sites.
3. **When a third SCM provider arrives (GitLab, Gitea, Forgejo, etc.):** always prefer MCP over hand-rolling a client. `SCMClient` Protocol + `MCPSCMAdapter` becomes the standard add-a-provider path.

## Coverage matrix — GitHub MCP vs our `SCMClient` Protocol

Audited against `github/github-mcp-server` main branch toolset as of 2026-04-24.

| `SCMClient` method | GitHub MCP tool | Notes |
|---|---|---|
| `list_files` | `get_file_contents` (dir mode) or `get_repository_tree` | directory listing via `get_file_contents` on a path returns the directory entries |
| `read_file` | `get_file_contents` (file mode) | ✅ direct match |
| `search_code` | `search_code` | ✅ direct match |
| `create_branch` | `create_branch` | ✅ direct match |
| `create_or_update_file` (helper used by `file.write` tool) | `create_or_update_file` + `push_files` + `delete_file` | ✅ direct match |
| `create_pull_request` | `create_pull_request` | ✅ direct match |
| `get_pull_request` | `pull_request_read` | ✅ direct match |
| `list_pull_requests` | `list_pull_requests` | ✅ direct match |
| `get_pr_diff` | — | ⚠️ **no direct equivalent.** `pull_request_read` returns PR metadata but not the unified-diff `files[]` array. `get_commit` returns a single commit's diff. Options: (a) call REST `/pulls/{n}/files` via `raw_github_request` if the server exposes it; (b) compose from `list_commits` + `get_commit_diff` per commit (O(n) API calls); (c) **keep our direct httpx call for this one method** behind the adapter, falling through to REST. Recommend (c) for Phase A. |
| `post_pr_comment` | `add_comment_to_pending_review` + `pull_request_review_write` | ✅ covered. Slightly different workflow (pending review → submit) but semantically equivalent to our inline comment. |
| `merge_pull_request` | `merge_pull_request` | ✅ direct match |
| `approve_pr` | `pull_request_review_write` with `event="APPROVE"` | ✅ covered |
| `request_changes` | `pull_request_review_write` with `event="REQUEST_CHANGES"` | ✅ covered |
| `get_commit_diff` | `get_commit` | ✅ direct match (GitHub returns diff in commit payload) |
| `list_recent_merges` | `list_pull_requests` with `state="closed"` + filter on `merged_at` | ✅ covered (client-side filter, same as today) |
| `get_pr_reviewers` | `pull_request_read` returns `requested_reviewers`; list reviews via separate tool | composition needed (same as our current GitHub client merges two API calls) |
| `get_pr_checks` | `actions_list` + `get_job_logs`? | ⚠️ **partial.** GitHub Actions toolset exists but doesn't expose `/commits/{sha}/check-runs` directly. Options same as `get_pr_diff`: fall back to direct REST for this method. |
| `get_pr_commits` | `list_commits` scoped to PR | ✅ covered |

**Summary:** 13 of 15 clean matches. 2 methods (`get_pr_diff`, `get_pr_checks`) need either a REST fallback inside the adapter or composition from multiple MCP tools. Neither blocks adoption; we keep those two methods as direct REST calls in the adapter until GitHub MCP adds them.

## Phase A — GitHub adapter (scope: ~1 week)

### A1. MCP infrastructure prep

- [ ] Confirm `src/ai_driven/mcp/client_manager.py` can run `github-mcp-server` as a long-lived stdio subprocess per backend worker (it already runs `@modelcontextprotocol/server-github` for the old tool-bridge path — this is just a different binary)
- [ ] Add `github_mcp_binary: str = "github-mcp-server"` to `src/ai_driven/config.py` (env override for pinning version in CI)
- [ ] Add a health check that the subprocess responds to `tools/list` on startup; fail fast otherwise

### A2. `MCPSCMAdapter` class

- [ ] Create `src/ai_driven/scm/mcp_adapter.py` implementing `SCMClient` Protocol
- [ ] Constructor takes `(server_name: str, mcp_manager: McpClientManager, rest_fallback: httpx.AsyncClient | None)`
- [ ] Each Protocol method translates args → MCP tool name + arguments, calls `mcp_manager.call_tool(...)`, parses the response content back to our `PullRequest` / `DiffFile` / `PRReviewer` / `PRCheck` dataclasses
- [ ] `get_pr_diff` and `get_pr_checks` use `rest_fallback` (direct REST call) when the MCP server doesn't expose the needed tool
- [ ] Raise the same `SCMError` / `SCMAuthError` / `SCMNotFoundError` / `SCMRateLimitError` subclasses that `GitHubClient` raises today — callers do not change

### A3. Factory wiring

- [ ] Extend `get_scm_client_from_settings(scm_type)` in `src/ai_driven/scm/factory.py`:
  - If `scm_type == "github"` and `settings.scm_github_via_mcp` is `True` → return `MCPSCMAdapter(server_name="github", ...)`
  - Otherwise → return `GitHubClient(token=settings.github_token)` (current behavior)
- [ ] Add `AI_DRIVEN_SCM_GITHUB_VIA_MCP` env flag, defaulting to `False` for one release so we can roll back instantly
- [ ] No changes needed to `get_scm_client(scm_type, credentials)` (credential-based factory used in agent tool handlers) — it accepts a protocol-shaped client

### A4. Test coverage

- [ ] Unit test every `MCPSCMAdapter` method against a mocked `McpClientManager` — arg translation + result parsing
- [ ] Contract test: both `GitHubClient` and `MCPSCMAdapter` must return structurally identical `PullRequest`, `DiffFile`, `PRReviewer`, `PRCheck`, `PRCommit` objects for the same input. Run the same test cases through both and diff the results.
- [ ] Integration test against a throwaway GitHub repo: adapter + real MCP subprocess + real GitHub API
- [ ] All existing unit + integration tests must continue to pass with `AI_DRIVEN_SCM_GITHUB_VIA_MCP=true`

### A5. Rollout

- [ ] Deploy with flag `False` — zero runtime change
- [ ] Enable flag on staging for 48h; verify the admin `/reviews/{id}/*` endpoints, the task-page diff/file views, and the coding agent's `github.create_pr` path all work unchanged
- [ ] Enable flag in prod; monitor error rate, latency, MCP subprocess memory
- [ ] Delete `GitHubClient` after 1 release with flag enabled (prevents accidental regression)

### A6. Expected savings
- **~600 lines** removed from `src/ai_driven/scm/github_client.py` when we delete it
- **~100 lines** removed from Pydantic-style response parsers that duplicate GitHub's REST shape
- **1 fewer dependency** to audit — `tenacity` + raw `httpx` usage for GitHub becomes GitHub's responsibility

### A7. Risks
- **MCP subprocess lifecycle.** If the `github-mcp-server` subprocess dies mid-request, our MCP manager needs to reconnect. Current `McpClientManager` doesn't do supervised restarts. Add before enabling flag.
- **Rate limiting moves.** Today we own the 429 / rate-limit retry policy. With MCP in the middle, rate-limit errors come back as generic MCP tool errors. Need to detect and preserve retry semantics.
- **Token scoping.** MCP server takes a single `GITHUB_PERSONAL_ACCESS_TOKEN` env var. Multi-tenant token routing (per-team tokens) is harder with a single subprocess. If per-tenant tokens are needed, we need one subprocess per team (heavy) or the adapter has to accept a runtime token override. Defer this question until we hit a second tenant with its own GitHub org.
- **Tool naming drift.** GitHub has been iterating on MCP tool names. We pin the server version in `config.py` to avoid surprise breakage.

## Phase B — Bitbucket adapter (trigger-based, not time-based)

**Trigger:** Bitbucket ships an official MCP server OR a community server reaches "v1.0, tests present, published to a registry, covers 12+ of our 15 Protocol methods."

Until then: do **nothing**. Our hand-rolled `BitbucketClient` (~500 lines) is working and covers all 15 methods including the two GitHub-MCP-is-weak-on (`get_pr_diff` via `/pullrequests/{n}/diff`, `get_pr_checks` via `/pullrequests/{n}/statuses`).

### B1. Gating checklist (don't start until all four are true)

- [ ] Official Atlassian/Bitbucket MCP server exists **or** `mcp-atlassian` (the one we already registered for Jira dev tooling in `.mcp.json`) adds Bitbucket Cloud PR support
- [ ] Server covers: `get_file_contents`, `create_pull_request`, `list_pull_requests`, `get_pr_diff` equivalent, inline PR comments, approve/request-changes reviews, list commits on PR, PR statuses
- [ ] Server is on at least one release version (not `latest`-only)
- [ ] Server has at least one external non-vendor user we can cite as a reference

### B2. Implementation plan (identical shape to Phase A)

- [ ] Re-use the `MCPSCMAdapter` class from Phase A; instantiate with `server_name="bitbucket"`
- [ ] Add `AI_DRIVEN_SCM_BITBUCKET_VIA_MCP` env flag
- [ ] Extend `get_scm_client_from_settings("bitbucket")` to branch on the flag
- [ ] Same contract tests, same rollout sequence, same delete-after-one-release
- [ ] **~500 lines deleted** from `bitbucket_client.py`

### B3. If Phase B never triggers
If an official Bitbucket MCP never ships and no community server crosses B1 gate, we stay on `BitbucketClient` forever. That's fine — `SCMClient` Protocol was designed for exactly this case. `MCPSCMAdapter` and `BitbucketClient` can coexist indefinitely because they both implement the same interface.

## Out of scope for this doc

- Replacing our `JiraClient` with Jira MCP (separate trade-off; Jira MCP is a dev-tool today per `docs/mcp.md`, not wired into `CodingAgent._register_tools`)
- Moving DataDog / Slack integrations to MCP (no urgency; our clients for those are thin)
- Changing the Protocol itself — `SCMClient` is stable

## Open questions

- [ ] Does the GitHub MCP server stream responses for large diffs, or does it buffer? If buffered, very large diffs (>1 MB) may blow stdio buffers; need to confirm before Phase A rollout.
- [ ] Can we run one `github-mcp-server` subprocess per backend worker (FastAPI process) or do we need one per request? Performance test needed.
- [ ] How does MCP error taxonomy map to our `SCMError` hierarchy for the retry-sensitive cases (429, 5xx)?

## Acceptance criteria (Phase A)

- [ ] `AI_DRIVEN_SCM_GITHUB_VIA_MCP=true` — all existing Playwright e2e tests pass unchanged
- [ ] `AI_DRIVEN_SCM_GITHUB_VIA_MCP=true` — full coding agent run against a real Jira ticket completes: branch created, files written, PR opened, Jira commented
- [ ] `AI_DRIVEN_SCM_GITHUB_VIA_MCP=true` — review agent posts inline comments + submits approval review
- [ ] With the flag on, `grep -rn "GitHubClient" src/` shows zero production call sites (only tests and the one factory branch)
- [ ] Metrics: p95 latency for `get_pr_diff` and `get_pr_checks` within 1.5× of baseline (MCP adds a subprocess hop)
