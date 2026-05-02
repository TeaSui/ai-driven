# Action ↔ Observation Tool Pairing Audit

Reference: curriculum lesson 61 — *Environment Inspection*. Every tool that
mutates the world ("action") should be paired with at least one tool that
lets the agent verify the resulting state ("observation"). Without it, the
model has to assume its writes succeeded — that's the failure mode that turns
a 90 %-success agent into a 60 %-success one.

This document is the source of truth for the audit. Update it whenever a tool
is added.

## Scoring legend

| Status | Meaning |
|---|---|
| ✅ Paired | An observation tool exists and the agent uses it |
| ⚠️ Partial | The action's return value contains observation, but no separate read-back tool |
| ❌ Unpaired | No way for the agent to verify the action's effect |

## Coding agent ([src/ai_driven/agents/coding/agent.py](../../src/ai_driven/agents/coding/agent.py))

| Action tool | Observation tool | Status | Notes |
|---|---|---|---|
| `file.write` | `repo.read_file` | ✅ Paired | Agent can re-read after write. `repo.read_file` now supports batch `paths: list[str]` (up to 20 files per call, parallel) |
| — | `repo.overview` | n/a | New observation tool — returns `{tree, manifests}` in one call for whole-repo orientation without directory walks |
| — | `repo.list_files` | n/a | Now supports batch `paths: list[str]`, `recursive: bool` + `max_depth: int` tree walks, and `pattern: str` fnmatch filtering |
| `git.create_branch` | — | ❌ Unpaired | Add `git.list_branches` or `git.current_branch` so the agent can confirm the branch exists before pushing |
| `sandbox.execute` | (returns stdout/stderr) | ⚠️ Partial | Tool result is the observation. Acceptable, but the prompt must instruct the model to *read* it — current prompt does, keep an eye |
| `github.create_pr` | — | ❌ Unpaired | Add `github.get_pr` so the agent can verify URL and confirm the PR was created with the expected base/head |
| `bitbucket.create_pr` | — | ❌ Unpaired | Same as above for Bitbucket |
| `jira.update_status` | `jira.get_issue` | ✅ Paired | |
| `jira.add_comment` | `jira.get_issue` | ⚠️ Partial | `get_issue` does not always return comments. Either extend it or add `jira.get_issue_comments` |

## Reviewer agent ([src/ai_driven/agents/reviewer/agent.py](../../src/ai_driven/agents/reviewer/agent.py))

| Action tool | Observation tool | Status | Notes |
|---|---|---|---|
| `github.post_pr_comment` | — | ❌ Unpaired | Add `github.list_pr_comments` so the agent can confirm posts (and avoid duplicates on retry) |
| `bitbucket.post_pr_comment` | — | ❌ Unpaired | Same |
| `github.approve_pr` / `request_changes` | — | ❌ Unpaired | A `github.get_pr_reviews` would let the agent verify its review state and avoid duplicate submissions |
| `bitbucket.approve_pr` / `request_changes` | — | ❌ Unpaired | Same |

## DevOps agent ([src/ai_driven/agents/devops/agent.py](../../src/ai_driven/agents/devops/agent.py))

| Action tool | Observation tool | Status | Notes |
|---|---|---|---|
| `jira.create_incident` | `jira.get_issue` | ⚠️ Partial | Works if `get_issue` is available in the registry; not always registered for DevOps. Confirm wiring |
| `slack.notify_oncall` | — | ❌ Unpaired | Add `slack.read_thread` or rely on Slack's response delivery — for now treat as fire-and-forget and document |
| `deploy.suggest_rollback` | — | n/a | Suggestion only; no mutation |

## Forced final-summary turn

Independent of per-tool pairing, the agent loop itself enforces lesson 61 at
the framework level: when `max_iterations` is exhausted while the model still
wants tools, [`ClaudeClient.run_agent_loop`](../../src/ai_driven/claude/client.py)
now executes the pending tool calls one last time, then makes a single
no-tools synthesis call. The model is forced to emit a final text answer
based on what it actually observed. Without this, an agent that hits its
budget mid-investigation returned only `tool_use` blocks — callers got an
empty `output` and silently persisted nothing.

Tested in [`tests/unit/test_claude_client_max_iter_synthesis.py`](../../tests/unit/test_claude_client_max_iter_synthesis.py).

## Follow-ups (sorted by impact)

1. **Reviewer false-double-comment risk** — add `list_pr_comments` to GitHub +
   Bitbucket SCM clients and register as a tool. The reviewer can then
   skip findings already posted on retry.
2. **Coding `github.get_pr`** — same shape, also unblocks future agents that
   chain "create PR → wait for checks → merge".
3. **`git.list_branches`** — cheap, prevents the coding agent from pushing to
   a branch it never actually created.
4. **`jira.get_issue_comments`** — not strictly required if `get_issue`
   includes comments; verify and either patch the existing tool or add this.

Each follow-up is a small, focused PR. Reference this doc in the description
so it's clear *why* the read-back tool exists and not just *what* it does.
