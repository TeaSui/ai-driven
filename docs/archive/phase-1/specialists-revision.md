# Orchestrator + Specialists — Spec Revision

**Status:** Draft — supersedes selected sections of `2026-04-29-orchestrator-specialists-design.md`
**Author:** Session continuation, 2026-04-30
**Scope:** A delta on the Apr-29 design. Only sections that conflict with the H1-H5 landings on master are restated; everything not addressed here remains as written in the original spec.

## Why this revision exists

Between the original spec (Apr 29) and now (Apr 30), five hotfixes landed on master in response to the KAN-12 $6.25 cost incident:

| # | Commit | Change |
|---|---|---|
| H1 | 36a8780 | All three model slots pinned to `claude-sonnet-4-6`. `claude_default_model`, `claude_planning_model`, `claude_fast_model` all = Sonnet. Haiku is no longer wired into the runtime. |
| H2 | acaab5f | Per-task cost ceiling `claude_max_cost_per_task_usd = 3.0`. `BaseAgent.run()` gates the agent loop via `_should_continue` when cumulative spend crosses the ceiling. |
| H3 | 37391bb | Cache breakpoint pinned on the FIRST user message (stable prefix) — not the last. `messages` cache hits survive across iterations. |
| H4 | 91b7d4e | `_iteration_cost_usd` dispatches per-model pricing via `billing.tracker.get_model_pricing()`; Bedrock non-us-east-1 regions apply `bedrock_price_multiplier` (default 1.10). |
| H5 | 8fd2c3c | New repo tools: `repo.list_files` gains `paths[]` / `recursive` / `max_depth` / `pattern`; `repo.read_file` accepts `paths[]` for batch reads; new `repo.overview` returns `{tree, manifests, truncation_note}`; initial user message pre-loads a `<repo_map>` XML block. |

A subsequent change (42ddf91) also restructured the coding agent's initial user message into XML-tagged form per `prompt-engineering.md` (see `src/ai_driven/agents/coding/agent.py::_build_initial_message`). Specialist prompts must follow the same convention — the original spec predates this and specifies a markdown-header style.

The original spec's core architecture (Orchestrator → BA → StackAnalyst → Security → code+review → QA) is unchanged. This revision only touches the five sections where H1-H5 invalidated an assumption.

## Sections revised

### 1. Model selection per role (replaces the "Model selection per role" table)

Original spec routed the tier classifier, specialist picker, and `stack_analyst` to `claude-haiku-4-5`. Haiku is no longer available in the platform's model slots post-H1, and config values read through `get_settings()` all return Sonnet.

Revised table:

| Role | Model | Config key | Why |
|---|---|---|---|
| Tier classifier | `settings.claude_fast_model` (= Sonnet 4.6) | `claude_fast_model` | Was spec'd as Haiku; Haiku slot was removed in H1. Use `claude_fast_model` as the indirection so if/when a cheaper tier is re-introduced we flip one config value, not every specialist. Temperature 0, `max_tokens=16` (bumped from 8 per plan red-team), stop-sequence parsing. |
| Specialist picker | `settings.claude_fast_model` | `claude_fast_model` | Same rationale. Subset-pick call; output tokens ≤ 64. |
| `business_analyst` | `settings.claude_default_model` | `claude_default_model` | Unchanged from original spec (was Sonnet). |
| `stack_analyst` | `settings.claude_fast_model` | `claude_fast_model` | Multi-label classification over 7-item set. Sonnet is more than enough; keep the indirection. |
| `security` | `settings.claude_default_model` | `claude_default_model` | Unchanged. |
| `qa` | `settings.claude_default_model` | `claude_default_model` | Unchanged. |
| Stubs | n/a | — | No Claude calls. |

**Key rule:** specialists must never hardcode a model string. They resolve via `get_settings()` so a future Haiku re-introduction is a one-line flip per config slot. A unit test in Task 6 should import each specialist and assert its model resolves via `get_settings()` rather than a hardcoded literal — locks the rule against regressions.

**Cost implication:** every classifier and specialist call is now materially more expensive per call than the original spec projected (Sonnet input $3/M vs Haiku ~$0.25/M). Real dollar impact is still bounded:
- Tier classifier: ~800 input tokens × $3/M + ~16 output × $15/M ≈ $0.0026/call. Not cacheable (system prompt < 1024 tokens — see §4).
- Specialist picker: planned for Phase 2 only (see §Open Questions #1). Zero calls in MVP.
- Specialist calls (BA, stack_analyst, security): each ~$0.01–0.02 depending on cache hit. Cold call (first of the role within 1h TTL) ≈ $0.02; warm call benefits from ~$0.0003/1K cache-read pricing on the cached system prompt (90% discount, see H3 cache rules) and drops to ~$0.005–0.01.
- Expected orchestration overhead per `full_workflow` task: classifier (~$0.003) + 3 Sonnet specialist calls (~$0.01–0.02 each) ≈ **$0.03–$0.07 / task**. Fast-path tasks: classifier only (~$0.003).

These numbers remain well inside the $3.00 per-task ceiling (see §2.1 for the combined-scope reconciliation). No budget re-negotiation needed.

### 2. Cost kill-switch inheritance (new section — not present in original spec)

Every specialist runs through `BaseAgent.run()`, which means the `claude_max_cost_per_task_usd = 3.0` ceiling applies to the whole orchestration run, not per specialist.

**Design rule:** the orchestrator maintains a running tally of cost across the classifier call + all specialist calls + the downstream coding/review pipeline, and short-circuits the entire run (not just the current specialist) when the ceiling is crossed.

Concretely:
- `OrchestratorAgent._run_specialist(role, context)` passes `running_cost_usd` into the specialist's `BaseAgent.run(initial_cost=…)`.
- `BaseAgent.run()` adds an `initial_cost: float = 0.0` kwarg so callers can pre-seed the accumulator.
- **Pre-iteration check is required.** `_should_continue` is only consulted BETWEEN iterations in `run_agent_loop` (see `claude/client.py:108`). If the pre-seeded budget is already over the ceiling, the first Claude call would still fire and waste money. Fix: `BaseAgent.run()` must short-circuit BEFORE invoking `run_agent_loop` when `initial_cost >= settings.claude_max_cost_per_task_usd`, returning `AgentResult(success=False, reason='budget_exceeded_on_entry', cost_usd=initial_cost)` with zero Claude calls. A Task 5 (or 5b) test must assert no Claude call is made when the budget is pre-seeded over the ceiling.
- On `budget_exceeded` return from any specialist, orchestrator writes `Artifact(verdict='error', reasoning='budget_exceeded after <role>', payload={})` and halts.
- In shadow mode (Phase 1), the orchestrator is fire-and-forget; a budget-exceeded return just truncates the observation window. It does NOT affect the coding pipeline's own ceiling directly. BUT shadow-mode spend must still be accounted — see §2.1 and §2.2.
- In live mode (Phase 2), the accumulator is shared — the coding pipeline reads the orchestrator's `running_cost_usd` as its starting point. This ensures we never spend $3 on orchestration and then another $3 on coding.

**Task addition required (Phase 1 plan):** add `initial_cost` kwarg to `BaseAgent.run()` in Task 5 (or as a separate Task 5b). The plan's current Task 5 only lifts the repair loop; cost-pre-seeding is a new requirement from H2.

### 2.1 Combined-scope ceiling semantics (Phase 2)

`claude_max_cost_per_task_usd = 3.0` is authoritative for the COMBINED running_cost_usd once orchestration goes live. It is NOT a per-agent budget. Phase 2 implications:
- Orchestration overhead (~$0.03–$0.07) counts against the $3 ceiling.
- Coding + review inherit the remaining headroom (~$2.93 typical).
- The per-specialist `MAX_INPUT_TOKENS = 8192` refuse-to-run threshold (original spec line 475) stays as a complementary defense against individual-call blowup, but it does NOT substitute for the dollar ceiling — both checks apply independently.
- The docstring on `claude_max_cost_per_task_usd` in `config.py` must be updated to say "combined across orchestrator, specialists, and coding/review pipeline" as part of the Phase 2 rollout PR. No change needed in Phase 1 (shadow mode).

### 2.2 Shadow-mode cost observability (resolves original Open Question #2)

Shadow-mode fire-and-forget spends real money on every task that's never visible on the "Cost attribution" dashboard (original spec §Observability, line 562). For a 1-week shadow run at 500 tasks/day × $0.05/task average orchestration overhead ≈ $175 untracked spend. KAN-12 lived here; this is not acceptable.

**Required:** persist orchestration spend even in shadow mode.
- Add column `task_artifacts.cost_usd NUMERIC(10,6) DEFAULT 0` in the Task 1 migration (ALTER to add if commits are cherry-picked — otherwise include in the initial migration).
- `_OrchestratorWrapper` sums classifier + specialist costs and writes the per-specialist value into each row plus the classifier total into a new `orchestration_runs` summary row (or a single `tasks.orchestration_shadow_cost_usd` column — pick one; recommendation: the column, it's simpler for Phase 1 and the per-specialist cost already lives on the artifact row).
- The "Cost attribution" dashboard (original spec §Observability item 2) gets a new panel: "Shadow-mode orchestration spend / day" sourced from `SUM(task_artifacts.cost_usd) GROUP BY date_trunc('day', created_at)`.
- This column survives into Phase 2 live mode and becomes the per-specialist cost breakdown.

### 3. Prompt structure (revises "Prompt template (all specialists)")

Original spec showed markdown-header prompts (`# ROLE`, `# WHAT YOU PRODUCE`, etc.). Post-42ddf91, coding and devops agents use XML-tagged prompts per `prompt-engineering.md`. Specialist prompts must follow the same convention for consistency and to benefit from the same cache rules.

Revised specialist prompt template:

```
You are a {role} agent. [one-line mandate.]

<role_responsibilities>
[bulleted mandate specific to the role]
</role_responsibilities>

<input_schema>
You will receive:
  - <jira_ticket>: ticket fields
  - <repository>: repo metadata
  - <prior_artifacts>: JSON objects from upstream specialists (may be empty)
</input_schema>

<output_schema>
Single JSON object:
{schema}
- verdict: 'pass' | 'pass_with_notes' | 'block'
- reasoning: 1-2 sentences
- payload: {role schema fields}
</output_schema>

<decision_rules>
[role-specific rules]
</decision_rules>

<examples>
<positive_example>…</positive_example>
<blocker_example>…</blocker_example>
</examples>

<anti_patterns>
[what this role does NOT do — explicit list pointing at adjacent specialists]
</anti_patterns>
```

The anti-patterns block stays; it's the key anti-drift mechanism. Only the syntax changes.

**Prefill + stop_sequences unchanged:** the runtime still uses

```python
messages=[
    {"role": "user", "content": user_content},
    {"role": "assistant", "content": "```json\n{"},
],
stop_sequences=["```"],
```

as described in the original spec. This works because specialists do NOT use extended thinking (which would make prefill incompatible — see planner.py's docstring for the rule).

**Extended thinking rule:** no specialist in this spec enables extended thinking. The planner agent uses it because it benefits from long-form reasoning; specialists produce short structured JSON where thinking's cost is not justified.

### 4. Prompt caching (revises "Prompt caching" subsection)

Original spec said "System prompt must exceed 1024 tokens to be cacheable". That's correct. Post-H3 we also know that:
- The stable-prefix breakpoint belongs on the FIRST user message when `messages` are cached, not the last. `_with_cache_control_messages` in `claude/provider.py` already does this for all agents.
- Specialists' `messages` list is: `[{role: user, content: <jira + repo + prior_artifacts>}, {role: assistant, content: "```json\n{"}]`. The prefill assistant message is identical across every specialist run, so a cache breakpoint on the user message is safe even if the prefill byte-repeats — the cache key is the prefix through the breakpoint, and the assistant prefill sits AFTER it.
- `<prior_artifacts>` content changes per task, so the user-message breakpoint is per-task only. It protects against multi-round repair loops (which send the same user context again) but does not cache cross-task.

**Revised cache layout per specialist:**

```python
response = await client.chat(
    model=settings.claude_default_model,  # or claude_fast_model
    max_tokens=2048,
    system=[{
        "type": "text",
        "text": SPECIALIST_SYSTEM_PROMPT,  # 1500-2500 tokens, stable across all runs of this role
        "cache_control": {"type": "ephemeral"},
    }],
    messages=[
        {"role": "user", "content": user_content},     # per-task; breakpoint auto-added by provider
        {"role": "assistant", "content": "```json\n{"},
    ],
    stop_sequences=["```"],
)
```

**Two-to-three cache breakpoints per specialist call:**
- `_with_cache_control_system` (provider.py:227) marks the system prompt as ephemeral when present — always present for specialists.
- `_with_cache_control_tools` (provider.py:229) marks the LAST tool schema when tools are passed — present only for `stack_analyst` (which uses `repo.overview`) and for QA (which uses `github.get_pr_diff`, `repo.read_file`).
- `_with_cache_control_messages` (provider.py:223) marks the FIRST user message.

Net count: BA / security / stub specialists → 2 breakpoints. stack_analyst / QA → 3 breakpoints. All well under Anthropic's 4-breakpoint limit.

Expected hit rates: system prompt ~95% cross-task within 1h, first user message ~50% within a repair loop (round 2 repeats context), tool schemas ~95% cross-task (stable).

**Not cached:** tier classifier's system prompt (too short — sub-1024 tokens).

### 5. Repo access for `stack_analyst` (revises "Tools per specialist" row)

Original spec gave `stack_analyst` a `repo.list_files` tool (read-only). Post-H5, `repo.list_files` is now batched/recursive/glob-capable and `repo.overview` returns `{tree, manifests, truncation_note}` in a single call.

**Revised tools for `stack_analyst`:** `repo.overview` only.

Why: `repo.overview` is a strictly better fit for stack classification. It hands the model a one-shot tree snapshot + top-level manifest files (`package.json`, `pyproject.toml`, `go.mod`, etc.) which are exactly the signals a stack classifier cares about. Eliminates the 2-3 `list_files` tool calls the original spec's `stack_analyst` would have chained.

**Token cost:** `repo.overview` response caps at ~2K tokens (tree truncated by depth, manifests truncated to 10KB each). Fits comfortably inside a single specialist iteration; no extra iterations needed.

**Refusal rule:** `stack_analyst` may NOT call `repo.read_file` or any other SCM tool. If it needs more context than `repo.overview` provides, it reports low-confidence reasoning and proceeds with a conservative classification. This keeps the specialist bounded (~1 iteration, ~$0.003 at Sonnet fast-model rates).

### 5.1 QA tool names (corrects original spec line 457)

Original spec QA row lists `scm.read_diff`, `scm.read_file`, `tests.list_run_results` — none of these tool names exist on master. Actual tool names available in the registry (from `src/ai_driven/claude/tools/` registrations):

- `github.get_pr_diff` — structured PR diff
- `repo.read_file` — batch-capable file read (post-H5)
- `repo.overview` — tree + manifests
- No `tests.list_run_results` exists today — dropped from MVP.

**Revised QA tools:** `github.get_pr_diff`, `repo.read_file`, `repo.overview`. Test-result inspection is deferred until a `tests.list_run_results` tool is introduced (out of MVP scope).

### 6. Initial-message XML structure for specialists (new section)

Per 42ddf91, the first user message to each specialist uses the XML-tagged template below. This is the runtime convention post-revision.

```
<task_role>{role}</task_role>

<jira_ticket>
  <title>{ticket.summary}</title>
  <description>{ticket.description}</description>
  <acceptance_criteria>{ticket.acceptance_criteria or '<empty>'}</acceptance_criteria>
  <labels>{ticket.labels}</labels>
</jira_ticket>

<repository>
  <full_name>{repo.full_name}</full_name>
  <scm_type>{repo.scm_type}</scm_type>
  <default_branch>{repo.default_branch}</default_branch>
</repository>

<prior_artifacts>
{json.dumps(prior_artifacts_dict, indent=2) or '<none>'}
</prior_artifacts>

<instruction>Produce the JSON object per <output_schema>. Use prefill to start.</instruction>
```

This structure MUST be implemented in `specialists/base.py::_build_initial_message()` (Task 6 in the plan — not yet on master). Individual specialist classes MUST NOT override it.

### 7. Terminology sweep across original spec (replaces stale Haiku / $0.0001 references)

The original spec still contains pre-H1 assumptions in sections §"Sections NOT revised" nominally left alone. Treat this revision as authoritative; read the original spec lines below with these substitutions:

| Original spec line | Original text | Replace with |
|---|---|---|
| 18 (Goals) | "a single Haiku classifier call (~$0.0001)" | "a single classifier call (~$0.003 on Sonnet fast-model post-H1)" |
| 38 (Core decisions #7) | "Cheap Haiku classifier" | "Cheap classifier (settings.claude_fast_model, currently Sonnet post-H1)" |
| 53 (System diagram) | "Classify (cheap Haiku call ...)" | "Classify (cheap settings.claude_fast_model call ...)" |
| 210 (§Layer 1) | "Single Haiku call" | "Single settings.claude_fast_model call" |
| 213 (§Layer 1) | "Cost: ~$0.0001/task on Haiku" | "Cost: ~$0.003/task on Sonnet fast-model" |
| 254 (§Layer 3) | "One Haiku call" | "One settings.claude_fast_model call" |
| 509 (§Evaluation) | "Haiku-based grader" | "Sonnet-based grader (settings.claude_default_model; grader slot also converged on Sonnet in H1)" |

These are read-time substitutions; the original spec file is left intact as a historical record. Any direct implementation reader should prefer this revision where the two conflict.

## Sections NOT revised (explicit)

Everything else in `2026-04-29-orchestrator-specialists-design.md` stands as written, subject to the terminology sweep in §7 above:

- Core architecture (orchestrator → specialists → artifacts → coding → review → QA gate)
- Data model (`task_artifacts` table, `tasks.orchestration_tier` column)
- Routing rules (tier classifier → routing.py → specialist picker)
- Fan-out matrix (which specialists read which prior artifacts)
- Specialist mandates (BA, StackAnalyst, Security, QA live; TechLead, UXDesigner, APITest stubs)
- Stack-aware context injection (composer + stack_guides library)
- Failure handling (tiered, blocker verdicts sacred)
- Testing strategy
- Rollout phases (shadow → per-team live → WorkflowAgent deletion)
- Observability (3 DataDog dashboards)

## Plan-document changes required

The existing plan at `docs/plans/2026-04-29-orchestrator-build.md` needs these edits before execution resumes:

| Plan section | Edit |
|---|---|
| Task 5 | Change "lifts JSON repair loop" to "lifts JSON repair loop AND adds `initial_cost: float = 0.0` kwarg to `BaseAgent.run()`". Add a test that pre-seeded cost causes `_should_continue` to short-circuit immediately when initial_cost >= ceiling. |
| Task 6 (`SpecialistBase`) | Model IDs resolve via `get_settings().claude_default_model` / `claude_fast_model`. No hardcoded "claude-haiku-4-5" strings. `_build_initial_message` uses the XML template from §6 above. |
| Task 8 (BA) | System prompt uses XML-tagged template from §3. |
| Task 9 (stack_analyst) | Tools list = `["repo.overview"]` only. System prompt uses XML template. |
| Task 10 (security) | System prompt uses XML template. |
| Task 11 (QA) | System prompt uses XML template. |
| Task 12 (classifier) | Model = `settings.claude_fast_model` (not "claude-haiku-4-5"). `max_tokens=16`. |
| Task 16 (`OrchestratorAgent`) | Threads `running_cost_usd` through every specialist call via `initial_cost` kwarg. On any specialist's `budget_exceeded` result, write `Artifact(verdict='error', reasoning='budget_exceeded after <role>')` and halt. Picker call (if implemented under this task rather than deferred to Phase 2) uses `settings.claude_fast_model`, `max_tokens=64`. Plan currently stubs the picker to `return []` (line 3393) — leave stubbed for MVP per §Open Questions #1. |
| Task 17 (wiring) | No change — shadow-mode fire-and-forget path is unaffected. |
| Task 1 migration | Add `cost_usd NUMERIC(10,6) DEFAULT 0` column to `task_artifacts` per §2.2. (If T1 commit is cherry-picked before this revision applies, follow up with an additive ALTER migration.) |
| Task 5 | Also remove the `_update_task_status('needs_human_review')` call at `reviewer/agent.py:648` — it violates the `ck_tasks_status` CHECK constraint. Test: reviewer repair-exhausted path does NOT attempt that status transition. (The deleted T5 commit 0aae9b9 already includes this fix; cherry-pick cleans both problems at once.) |
| Task 5 | Also add `initial_cost: float = 0.0` kwarg to `BaseAgent.run()` with pre-iteration short-circuit per §2. |

Tasks 1-4 (migration, ORM, schemas, repository) are unchanged. Their commits are still in `git reflog` (0aae9b9, fff8b9a, 5b4c168, 2fd17a7, 2c34dd3) from the deleted `feature/orchestrator-phase1` branch and can be cherry-picked back once the plan edits above are applied.

Task 5 from the deleted branch also cherry-picks cleanly but is insufficient on its own under this revision — the `initial_cost` kwarg addition is a new requirement. Easiest path: cherry-pick the commit, then land a follow-up commit that adds the kwarg + test.

## Open questions (explicit)

1. **Does the picker ever run in MVP?** In the original spec, `pick_from` for `full_workflow` was `["tech_lead", "ux_designer"]` — both stubs. Layer 3 picker is skipped when all candidates are stubs. Net effect in MVP: picker code is never invoked for a live specialist. **Resolution:** leave the picker stubbed (plan line 3393 already does this with `return []`). Ship the model-resolution infrastructure (use `settings.claude_fast_model` when implemented) but do NOT wire the call in Phase 1. Test: one unit test asserting `_pick_optional_specialists` returns `[]` for every tier in MVP.

2. **~~Cost accumulator sharing in shadow mode.~~** Resolved in §2.2 — shadow-mode spend lands in `task_artifacts.cost_usd` and surfaces on the cost-attribution dashboard.

3. **~~Reviewer latent bug.~~** Resolved — promoted to a required Task 5 edit in the plan-changes table above.

## Next step

If approved:
1. Cherry-pick T1-T4 from reflog (0aae9b9's predecessors: 2c34dd3, 2fd17a7, 5b4c168, fff8b9a).
2. Apply plan edits from "Plan-document changes required" to `docs/plans/2026-04-29-orchestrator-build.md` (in-place).
3. Cherry-pick T5 (0aae9b9).
4. Land Task 5b (new): `BaseAgent.run(initial_cost=...)`.
5. Resume plan execution from Task 6 via `subagent-driven-development`.

Estimated residual work: T6-T17 is ~10-12 days of focused engineering (specialists, prompts, orchestrator, wiring, tests) — the residual implementation cost is the original T6-T17 estimate plus ~0.5 day for the revision-driven edits.
