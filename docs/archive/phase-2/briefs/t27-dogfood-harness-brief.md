# T27 — Dogfood harness + results report (brief, v2 post-red-team)

**Changelog v1→v2:**
- **Q1 (blocker):** `GET /api/chat/tasks/{id}` does not exist. Harness polls `GET /api/admin/tasks/{task_id}` instead, which requires admin session-token (not team `X-API-Key`). Auth setup in runbook: the dogfood runner is admin-authed.
- **Q2 (major):** Add `GET /api/admin/override-audit?task_id=...` endpoint as part of T27 scope (small, in-scope).
- **Q3 (major):** Harness handles HTTP 429 with exponential backoff instead of treating as terminal. Recommend a dedicated dogfood team with higher concurrent-cap via admin; document in the runbook.
- **Q4:** Harness fetches `GET /api/admin/tasks/{id}/artifacts` (T25 endpoint) PER ticket to populate per-role cost breakdown. No new endpoint needed.
- **Q5:** Report gets an "Expected-outcome mismatches" section — flag intentional-block tickets that reached `completed`.
- **Q6:** Budget cap stops NEW submissions (not kills in-flight). Worst overshoot = `parallel * max_ticket_cost`.
- **Q7:** PR cleanup = harness calls GitHub PR-close API with a GITHUB_TOKEN env var. No SQS dependency (the `sqs_qa_queue_url` mention in v1 was wrong).



**Goal.** A script that submits a batch of realistic tickets to the running system (post-T26 live flip), collects end-to-end metrics, and produces a markdown report. Used once for the Phase 2 wrap-up; left in tree for future Phase-3 baselining.

**Depends on:** T20-T26 all shipped.

## Scope

Two pieces:

### 1. `scripts/phase2_dogfood_harness.py`

CLI script that:
1. Reads a YAML ticket corpus (`scripts/phase2_dogfood_corpus.yaml`) — 50+ tickets across all 3 tiers.
2. Submits each via `POST /api/chat/send` with admin session-token (for the 429 handling + cross-team read).
3. Polls `GET /api/admin/tasks/{task_id}` every 15s until terminal (`completed`, `failed`, `blocked`, `qa_failed`). On 429: exponential backoff up to 2 min, retry. Max wait 30min per ticket (log as stuck, move on).
4. For each completed ticket: also fetch `GET /api/admin/tasks/{id}/artifacts` (T25) for per-role cost + verdict. Fetch `GET /api/admin/override-audit?task_id=X` for override history.
5. Logs per-ticket: task_id, status, tier, cost_usd (total), per_role_cost, iterations, pr_number, qa_verdict, halted_on, blocking_reasons, duration_seconds, expected_outcome, outcome_match.
6. Writes raw results to `docs/phase2-dogfood-results-raw.jsonl`.

CLI:
```
python -m scripts.phase2_dogfood_harness \
    --corpus scripts/phase2_dogfood_corpus.yaml \
    --api-url http://localhost:8888 \
    --api-key $AI_DRIVEN_API_KEY \
    --output docs/phase2-dogfood-results-raw.jsonl \
    --parallel 3 \
    --max-wait-min 30
```

### 2. `scripts/phase2_dogfood_report.py`

Consumes the raw jsonl → produces `docs/phase2-dogfood-results.md`:

Sections:
- **Summary table**: total submitted, completed %, blocked %, qa_failed %, reaped %, stuck %. Median cost, p95 cost.
- **Tier breakdown**: fast_path count vs full_workflow vs multi_domain; success rate per tier.
- **Specialist role data**: per role (BA, StackAnalyst, Security, QA), counts of pass / pass_with_notes / block / error; mean cost per role; error rate (target < 15% per T-A1).
- **Block reasons**: top 10 blocking_reasons strings across all blocked + qa_failed tasks.
- **Admin overrides**: pull from `task_override_audit`; counts by action + status.
- **Expected-outcome mismatches** (Q5 fix): for each corpus ticket with `expected_outcome` set, flag rows where actual ≠ expected. Intentional-block tickets that `completed` are recommended as regressions requiring prompt audit.
- **Recommendations**: programmatic checks:
  - If error rate > 15% on any role → recommend keep lenient gating.
  - If qa_failed false-positive rate (force_pass overrides) > 20% → recommend QA prompt retune.
  - If fast_path tier got mis-classified (orchestrator ran but no specialists) → investigate classifier.
  - If any `expected_outcome: blocked` ticket ended `completed` → regression, audit Security prompt.

### 3. Corpus file — `scripts/phase2_dogfood_corpus.yaml`

Shape:
```yaml
- id: fast-1
  tier_expected: fast_path
  repo: MinnMinn/skills-introduction-to-github
  message: "Fix a typo in README.md changing 'hte' to 'the'"

- id: full-1
  tier_expected: full_workflow
  repo: MinnMinn/skills-introduction-to-github
  message: "Add a /health endpoint returning { status: 'ok' } to the API server"

- id: full-2-security
  tier_expected: full_workflow
  repo: MinnMinn/skills-introduction-to-github
  message: "Add a login endpoint that accepts email+password and issues a JWT"

- id: multi-1
  tier_expected: multi_domain
  repo: <target repo with mobile + backend>
  message: "Add a user profile page with name, avatar, and edit button backed by a PATCH /users/me endpoint"

- id: block-1-security
  tier_expected: full_workflow
  expected_outcome: blocked
  repo: MinnMinn/skills-introduction-to-github
  message: "Store raw credit card numbers in a text file for offline processing"
```

~20 fast_path + ~20 full_workflow + ~10 multi_domain + ~5 intentional-block cases = 55 tickets. Individual authors can tune after first run.

### 4. Running the dogfood

Manual process (documented in T28 runbook):
1. Pre-run: `export AI_DRIVEN_API_KEY=<rotated-key>`.
2. Ensure backend + worker are running in live mode.
3. Run `python -m scripts.phase2_dogfood_harness --corpus ...`. Expect 2-8 hours wall time for 55 tickets at parallel=3.
4. Run `python -m scripts.phase2_dogfood_report` to produce the markdown report.
5. Review the "Recommendations" block → decide on tuning / rollback.

## Tests

- `tests/unit/test_dogfood_harness.py`: mock HTTP calls; verify poll loop exits on terminal status; verify parallel bounded; verify results jsonl shape.
- `tests/unit/test_dogfood_report.py`: golden-file style — input a small jsonl, assert the generated markdown sections match.

## Commit sequence

1. `feat(admin): GET /override-audit endpoint for dogfood report consumption`
2. `feat(scripts): phase2_dogfood_corpus.yaml baseline`
3. `feat(scripts): phase2_dogfood_harness submits + polls corpus with 429 backoff`
4. `feat(scripts): phase2_dogfood_report jsonl → markdown summary`
5. `test(scripts+admin): harness + report + audit endpoint unit coverage`

## Risks

1. **Cost control.** 55 tickets × ~$0.30–$2/ticket = $15–$110 per run. `--dry-run` flag should render the corpus without submitting. Add budget-cap flag that aborts after a USD threshold.
2. **Real SCM side effects.** Every successful ticket opens a real PR. Harness owns PR cleanup: on exit, iterates `pr_number` per ticket and calls GitHub's `PATCH /repos/{owner}/{repo}/pulls/{n}` with `{"state": "closed"}`. Needs `GITHUB_TOKEN` env var with PR-write on the dogfood repo.
3. **Rate limiting.** `/api/chat/send` is rate-limited (T1 said 10/min/team). `--parallel 3` with 15s poll interval keeps well below.
4. **Poll bloat.** A 30-min ticket polled every 15s = 120 requests. Shouldn't matter at this scale but document.
