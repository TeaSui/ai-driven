# T25 — Specialists UI tab + override button (brief, v2 post-red-team)

**Changelog v1→v2:**
- **#1 T24 refactor:** Do NOT rewrite T24's `_validate_action_for_status`. Add `available_actions(task_row)` as a NEW function in a shared helpers module that reuses T24's existing `_is_stuck` / `_parse_iso_ts` helpers. T25 GET uses it; T24 POST keeps its inline validation untouched.
- **#2 team_id check:** Use `require_admin().team_id` (confirmed present on `TeamMemberInfo`). Cross-team access returns 404 (not 403 — avoid existence leak).
- **#3 Decimal→str:** Pydantic response DTO types `costUsd: str`; endpoint casts via `str(artifact.cost_usd)` at build time. Simple + explicit.
- **#4 Tabs:** Do NOT add headless-ui. Hand-roll tabs as `useState` + Tailwind-styled button row, matching existing modal patterns in `new-run-modal.tsx`.
- **#5 Refresh:** Call `mutate` on the SWR keys for task + artifacts (SWR is already a dep). No `window.location.reload`.
- **#6 New repo method:** Add `ArtifactRepository.list_rows_for_task(task_id) -> list[TaskArtifact]` (ORM rows with `id`/`created_at`). Existing `list_for_task` returns `dict[str, Artifact]` dataclasses — inadequate. Order: `ORDER BY created_at ASC, role ASC`.
- **#7 qaFailures rendering:** Each entry renders with `criterion || rule` as title, `reason` body, collapsed `evidence`, optional severity badge. Two kinds (`ac_unsatisfied`, `security_rule_unfollowed`) distinguished by `kind` field.
- **#8 force_pass confirm:** secondary confirm dialog for force_pass specifically (skips QA entirely). Other actions: single modal.



**Goal.** Make orchestrator + QA output visible to humans. Admin sees:
1. A "Specialists" tab on the task detail page listing each artifact (role, verdict, reasoning, cost, payload JSON).
2. An "Override block" button on `blocked` / `qa_failed` / stuck-`awaiting_qa` / stuck-`orchestrating` tasks that calls T24's `POST /override-block`.

**Depends on:** T24 (override endpoint + require_admin + audit table).

## Backend (1 new endpoint + 1 extension)

### 1. `GET /api/admin/tasks/{task_id}/artifacts`

`src/ai_driven/api/admin/router.py`. Requires `require_admin` (T24 dependency).

Response (camelCase per `_shared.CamelModel`):
```json
{
  "data": {
    "taskId": "...",
    "classifierTier": "full_workflow",
    "artifacts": [
      {
        "id": "uuid",
        "role": "business_analyst",
        "verdict": "pass",
        "reasoning": "...",
        "payload": {...},
        "modelUsed": "claude-sonnet-4-5",
        "inputTokens": 1234,
        "outputTokens": 567,
        "costUsd": "0.0123",
        "createdAt": "2026-05-01T14:58:59Z"
      },
      ...
    ],
    "qaVerdict": "pass" | "block" | null,
    "qaFailures": [...] | null,
    "canOverride": true,
    "overrideActions": ["rerun_coding"]   // empty if current status not overrideable
  },
  "meta": {"timestamp": "..."}
}
```

Implementation:
- Call new `ArtifactRepository.list_rows_for_task(task_id) -> list[TaskArtifact]` (ORM rows; `ORDER BY created_at ASC, role ASC`).
- `task.team_id == require_admin().team_id` — else 404 (existence-leak avoidance).
- `task.output_data` for qaVerdict/qaFailures/classifierTier.
- `overrideActions` = `available_actions(task_row)` (shared helper — see §2).
- `canOverride = len(overrideActions) > 0`.
- Response DTO has `costUsd: str` (cast at build time to preserve Decimal precision).

### 2. `admin/override_rules.py` (shared helpers, additive)

NEW module that REUSES T24's existing `_is_stuck` + `_parse_iso_ts` helpers from `admin/override.py`. Exposes:

```python
OVERRIDEABLE_ACTIONS: dict[str, list[str]] = {
    "blocked": ["rerun_coding"],
    "qa_failed": ["rerun_qa", "force_pass"],
    "orchestrating": ["reap"],
    "awaiting_qa": ["reap"],
    "running": ["reap"],
}

def available_actions(task_row) -> list[str]:
    """UI-hint only. Returns the subset of actions offerable RIGHT NOW,
    incorporating the >30min stuck threshold for reap actions.
    T25 endpoint uses this. T24 POST endpoint keeps its own
    _validate_action_for_status inline — do NOT consolidate (regression risk
    on 29 T24 tests)."""
```

Move `_is_stuck` / `_parse_iso_ts` from `override.py` to a new `_overrides_helpers.py` that both `override.py` (T24) and `override_rules.py` (T25) import. That's a refactor; write it in a prep commit + rerun T24 tests to confirm green.

## Frontend

### 3. `ai-driven-web-ui/src/lib/api.ts` — helpers

```typescript
export async function getTaskArtifacts(taskId: string) { ... }
export async function overrideTaskBlock(taskId: string, action: string, reason: string) { ... }
```

Both use the existing fetcher pattern with auth headers.

### 4. `ai-driven-web-ui/src/app/dashboard/tasks/[id]/page.tsx` — Specialists tab

Tabs on the task detail page (hand-rolled with `useState` + Tailwind button row; NO headless-ui — see v2 fix #4):
- Overview (existing)
- **Specialists (new)**
- Output (existing)

Specialists tab renders a list of `<ArtifactCard>` components, one per artifact:

```jsx
<ArtifactCard>
  <Header>
    <RolePill>{roleDisplayName(role)}</RolePill>
    <VerdictPill verdict={verdict} />
    <Cost value={costUsd} />
    <Tokens input={inputTokens} output={outputTokens} />
  </Header>
  <Reasoning>{reasoning}</Reasoning>
  <PayloadExpander collapsed>
    <pre>{JSON.stringify(payload, null, 2)}</pre>
  </PayloadExpander>
</ArtifactCard>
```

Verdict pill colors:
- `pass` → green
- `pass_with_notes` → amber
- `block` → red
- `error` → grey
- `not_implemented` → grey

Cost formatted as `$0.0123` (4 decimal places).

### 5. Override button (admin-only)

On the task detail header, if `canOverride === true`, show an "Override" dropdown with buttons for each action in `overrideActions`. Clicking:
1. Opens a modal asking for `reason` (required, ≤ 500 chars, textarea).
2. On submit → `overrideTaskBlock(taskId, action, reason)`.
3. For `force_pass`: secondary confirm dialog ("Skip QA and mark complete?") — this is the only action that bypasses correctness gates.
4. On success: toast "Override applied"; call SWR `mutate()` on task + artifacts keys to re-fetch.
5. On 409: toast "Task status changed, refresh and try again."
6. On 403: toast "Admin role required."
7. **qaFailures rendering** in the Specialists tab: each entry's title is `criterion || rule`, body is `reason`, `evidence` is collapsed-by-default, optional severity badge colored by value (high/critical=red, medium=amber, low=grey).

The button is ONLY rendered when the backend said `canOverride=true` — frontend does not duplicate the eligibility logic. (Red-team-proof pattern: server is authoritative.)

## Tests

### Backend
- `tests/unit/test_admin_artifacts_endpoint.py`:
  - admin can GET artifacts for any task in their team.
  - non-admin → 403 (reuses require_admin tests).
  - task with 0 artifacts → empty list, canOverride by status matrix.
  - task with qa_failed verdict → qaFailures populated.
  - stuck-orchestrating task → overrideActions includes "reap".
  - fresh orchestrating → overrideActions empty.

- `tests/unit/test_override_rules.py` (new shared policy module):
  - `available_actions` returns empty for completed/queued.
  - stuck-threshold logic for reap actions.

### Frontend
- `ai-driven-web-ui/__tests__/artifact-card.test.tsx`: renders verdict pill, reasoning, expander.
- `ai-driven-web-ui/__tests__/override-button.test.tsx`: renders when canOverride; hidden otherwise; modal submits reason.

## Commit sequence

1. `refactor(admin): extract _is_stuck/_parse_iso_ts to _overrides_helpers (T24 tests still green)`
2. `feat(admin): override_rules.available_actions (additive, T24 untouched)`
3. `feat(db): ArtifactRepository.list_rows_for_task returning ORM rows`
4. `feat(admin): GET /tasks/{id}/artifacts endpoint`
5. `feat(ui): getTaskArtifacts + overrideTaskBlock API helpers`
6. `feat(ui): Specialists tab (hand-rolled tabs) + ArtifactCard`
7. `feat(ui): Override button + reason modal + force_pass secondary confirm`
8. `test(ui+admin): artifact endpoint + override flow coverage`

## Risks / notes

1. **Payload JSON size.** Some specialists (Security threat model) return large payloads. The `<PayloadExpander>` is collapsed by default so first paint is fast.
2. **Team ownership check.** Admin can GET any task in THEIR team; cross-team leaks would be a P0. Endpoint MUST filter by `task.team_id == require_admin().team_id`.
3. **Override button permission** — backend is authoritative; UI hides the button when `canOverride=false`. But a malicious user could manually POST to `/override-block` — T24's `require_admin` catches that.
4. **Cost display** — `costUsd` comes from `task_artifacts.cost_usd` (Decimal). Serialized as string to preserve precision across JSON.
