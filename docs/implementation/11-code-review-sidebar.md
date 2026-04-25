# 11 — Code Review Sidebar: Real Reviewers & Checks

**Status:** planned → implementing (this PR)
**Date opened:** 2026-04-24
**Related docs:** [04-code-review-bot.md](04-code-review-bot.md), [10-ui-polish-and-scm-dual-support.md](10-ui-polish-and-scm-dual-support.md)

## Context

The `/dashboard/reviews` page renders a right-hand sidebar with three sections — **Reviewers**, **Checks**, **Linked** — that were hardcoded as demo data when the page first shipped:

- `ReviewerRow name="Mira Delgado" status="pending"`
- `ReviewerRow name="Jamie Ortiz" status="approved"`
- `CheckRow name="ci / unit" ok time="42s"` (seven fake rows)
- `Alert-8821 balance-cache errors` (fake Linked alert)

This makes every real review look identical and non-credible. The PR data needed to drive these panels is already available from the GitHub and Bitbucket APIs — we just never pulled it. This doc wires the sidebar to real data.

## Goal

Replace the hardcoded sidebar rows with **live PR reviewer state and CI check-run status** from the SCM provider the review was registered under (GitHub or Bitbucket Cloud).

The `Linked` panel's Jira row is already wired to `review.jiraKey`; only the hardcoded Alert row is removed.

## Non-goals

- Real-time updates via WebSocket (polling via SWR is sufficient; PRs don't churn faster than a 30s poll).
- Posting reviews via the sidebar (already covered by the existing `Approve` / `Request changes` buttons).
- Modeling DataDog alerts inside the Linked panel (separate feature — requires alert-to-review correlation).

## Data model additions

### `src/ai_driven/scm/models.py`

- [ ] Add `PRReviewer`:
  ```python
  @dataclass
  class PRReviewer:
      username: str
      display_name: str
      avatar_url: str | None = None
      is_bot: bool = False
      # "pending" | "approved" | "changes_requested" | "commented" | "dismissed"
      state: str = "pending"
  ```
- [ ] Add `PRCheck`:
  ```python
  @dataclass
  class PRCheck:
      name: str          # e.g. "ci / unit", "security / snyk"
      status: str        # "queued" | "in_progress" | "completed"
      conclusion: str | None   # "success" | "failure" | "neutral" | "cancelled" | "skipped" | "timed_out"
      duration_seconds: int | None = None
      details_url: str | None = None
  ```

## SCMClient protocol additions

### `src/ai_driven/scm/interface.py`

- [ ] Add `get_pr_reviewers(repo_full_name, pr_number) -> list[PRReviewer]`
- [ ] Add `get_pr_checks(repo_full_name, pr_number) -> list[PRCheck]`

## GitHub implementation

### `src/ai_driven/scm/github_client.py`

- [ ] `get_pr_reviewers` — merge data from two endpoints:
  - `GET /repos/{owner}/{repo}/pulls/{pr}/requested_reviewers` → pending reviewers (no review submitted yet)
  - `GET /repos/{owner}/{repo}/pulls/{pr}/reviews` → submitted reviews with states (`APPROVED`, `CHANGES_REQUESTED`, `COMMENTED`, `DISMISSED`)
  - Per-user latest review state wins; `is_bot` via `type == "Bot"`.
- [ ] `get_pr_checks`:
  - `GET /repos/{owner}/{repo}/pulls/{pr}` → extract `head.sha`
  - `GET /repos/{owner}/{repo}/commits/{sha}/check-runs` → `check_runs[]`
  - Map each: `name`, `status`, `conclusion`, `started_at`/`completed_at` → `duration_seconds`, `html_url` → `details_url`.

## Bitbucket Cloud implementation

### `src/ai_driven/scm/bitbucket_client.py`

- [ ] `get_pr_reviewers`:
  - `GET /2.0/repositories/{workspace}/{repo}/pullrequests/{id}` → `reviewers[]` + `participants[]`
  - `participants[].state` is `"approved"` / `"changes_requested"` / `null` (pending).
  - Bots have `type == "app"` in `user` payloads (best-effort).
- [ ] `get_pr_checks`:
  - `GET /2.0/repositories/{workspace}/{repo}/pullrequests/{id}/statuses`
  - Map each: `key` → name, `state` (`SUCCESSFUL`/`FAILED`/`INPROGRESS`/`STOPPED`) → status/conclusion, `url` → details_url.
  - Bitbucket statuses don't expose start/end timestamps reliably → `duration_seconds = None`.

## Admin API endpoints

### `src/ai_driven/api/admin/router.py`

- [ ] `GET /api/admin/reviews/{review_id}/reviewers`
  - Resolve review → `repo_full_name` + `pr_number` + `scm_type` (via `Review.task.repo_id`).
  - Call `scm.get_pr_reviewers(...)`.
  - Also inject the AI reviewer at index 0 (synthesized from the Review record itself: name=`"GoTyme-X AI"`, `is_bot=True`, state derived from `Review.status`, plus a comment count).
  - Response: `{ "reviewers": [PRReviewer, ...] }`.
- [ ] `GET /api/admin/reviews/{review_id}/checks`
  - Same resolution path.
  - Response: `{ "checks": [PRCheck, ...] }`.
- [ ] Both endpoints return `503` with a clear message if the SCM client can't be built (missing credentials).

## Frontend wiring

### `ai-driven-web-ui/src/lib/api.ts`

- [ ] Add types: `PRReviewer`, `PRCheck`.
- [ ] Add `getReviewReviewers(reviewId)` + `getReviewChecks(reviewId)`.

### `ai-driven-web-ui/src/app/dashboard/reviews/page.tsx`

- [ ] `ReviewSidebar` takes `review` and uses two SWR hooks:
  - `useSWR(review ? ['/reviews', review.id, 'reviewers'] : null, ...)` (30s refreshInterval)
  - `useSWR(review ? ['/reviews', review.id, 'checks'] : null, ...)` (30s refreshInterval)
- [ ] Delete the three hardcoded `ReviewerRow` / seven hardcoded `CheckRow` calls.
- [ ] Render a loading spinner row when the SWR data is `undefined`, an empty-state row when the array is empty, and the real rows otherwise.
- [ ] Remove the hardcoded "Alert-8821 balance-cache errors" anchor (leave only the Jira link driven by `review.jiraKey`).

## Testing

- [ ] Unit test for `_github_reviewer_state_merge(requested, reviews)` covering: pending-only, approved-then-changes-requested (latest wins), bot user detection.
- [ ] Unit test for the admin endpoints with mocked SCM client covering: review not found (404), missing SCM creds (503), successful reviewer and check responses.
- [ ] Playwright: open a review from seeded data, assert sidebar shows the AI reviewer row + at least one CI check row (not the hardcoded seven).

## Rollout

- [ ] Ship behind no flag — the old hardcoded data was never load-bearing.
- [ ] If the SCM call fails (rate limit, 404), sidebar shows an empty state rather than falling back to fake data (no reason to lie).
