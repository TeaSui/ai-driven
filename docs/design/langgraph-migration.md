# Migration Design: Custom Agent Stack → LangChain / LangGraph / LangSmith

**Status:** FINAL (v3) — survived 3 rounds of adversarial review. Round 1: 11 objections (3 blockers, 6 majors) resolved. Round 2: 3 required amendments (crash-window post-processing recovery, in-progress-thread `input=None` re-entry, multi-HITL-call structural handling) + 4 minors applied. Round 3: confirmed ALIGNED, no new defects.
**Date:** 2026-06-11
**Constraint (non-negotiable):** SQS transport, async HITL semantics (pause → persist → ACK → resume with **no LLM-call replay**), and shadow-mode safety MUST be preserved through every phase.

---

## 1. What we're replacing vs. what we're keeping

| Component | Today | After migration |
|---|---|---|
| Model layer | `claude/provider.py` (Anthropic direct + Bedrock, 712 LOC) | `langchain-anthropic` (`ChatAnthropic`) + `langchain-aws` (`ChatBedrockConverse`), behind a thin factory |
| Tool loop | `ClaudeClient.run_agent_loop` (custom while-loop) | LangGraph graph: `agent` ⇄ `tools` ⇄ `hitl` nodes (§2), messages in checkpointed state |
| Tool registry | `claude/tools/registry.py` | LangChain `@tool` / `StructuredTool`; registry becomes a thin adapter during transition. **Port the dotted-name shim** — `human.ask_clarification` violates the Bedrock Converse `toolName` regex; the existing `_encode_tool_name`/`_decode_tool_name` (`provider.py:322-336`) moves into the adapter |
| HITL pause | `AwaitingHumanInput` BaseException → `task_specialist_state` snapshot → SQS ACK | LangGraph `interrupt()` inside a dedicated `hitl` node + Postgres checkpointer; SQS wrapper detects `__interrupt__` and ACKs |
| HITL resume | `/respond` → load state → append `tool_result` → continue | `/respond` → enqueue resume message → `graph.ainvoke(Command(resume=answers), config)` after idempotency guards (§2.3) |
| Orchestrator | `OrchestratorAgent.orchestrate()` imperative loop | LangGraph `StateGraph` (§3): classify → plan → specialist subgraphs → QA gate; halt-on-block as edge logic |
| Conversation persistence | `task_specialist_state` JSON snapshot | `AsyncPostgresSaver` checkpointer (library-managed `setup()` migrations, §5 Phase 2); `task_specialist_state` retained as a *projection* for the chat API (pending questions, round_id) |
| Tracing | DataDog APM + custom metrics | DataDog stays; **LangSmith added** for LLM-level traces, eval datasets (§4) |
| Cost/billing | `_iteration_cost_usd` + `billing/tracker` (Bedrock regional multiplier) | KEEP — fed from `AIMessage.usage_metadata` via callback handler; LangSmith does NOT replace this (no Bedrock regional uplift awareness) |
| SQS producer/consumer | `queue/consumer.py` (2098 LOC) | KEEP transport + idempotency guards; the agent-invocation surface and the `AgentResult` synthesis wrapper change (it currently reads `result.conversation.total_*` and `result.cost_usd`, `consumer.py:308-318` — a graph-output→`AgentResult` adapter is in scope for Phase 2) |
| Shadow mode | `orchestrator_mode != "live"` flag; orchestrator never gates pipeline | KEEP unchanged — orthogonal to graph engine |
| Error fallback | `orchestrator_treat_errors_as_pass` + fallback metrics | KEEP — edge logic + state field in the orchestrator graph |
| Structured JSON (≤2 repair rounds) | `BaseAgent.MAX_JSON_REPAIR_ROUNDS` | Per-agent decision table (§2.6) — NOT a blanket `with_structured_output` swap |
| Loop gates: budget ceiling, user stop/pause, forced synthesis on exhaustion | `base.py:517-539, 708, 728-737`; `client.py:202-246` | Explicit graph mapping (§2.5) — these are behaviors, not just a recursion_limit number |
| Streaming to UI (Redis pub/sub → WebSocket) | Buffered `streaming_chunk` + `tool_call_start/end` + `iteration` events from inside `BaseAgent.run` | `graph.astream(stream_mode=["messages","updates"])` mapped to the same event taxonomy (§2.4) |

## 2. HITL design (the load-bearing section)

### 2.1 Topology: three nodes, no LLM replay

LangGraph's `interrupt()` resumes by re-executing the interrupted **node** from its start. Therefore each LLM call must be its own node execution, and the interrupt must live in a node containing *nothing else that costs money or has side effects*.

```
agent ──(tool calls?)──▶ tools ──(batch contained ask_clarification?)──▶ hitl ──▶ agent
  ▲                        │ (no)                                          │
  └────────────────────────┴──────────────────────────────────────────────┘
  (no tool calls → END / structured-output path)
```

- **`agent` node**: one `ChatAnthropic`/`ChatBedrockConverse` call. Writes the `AIMessage` to state. Committed at the super-step boundary — never re-executed by a downstream interrupt.
- **`tools` node** (custom, NOT the prebuilt `ToolNode`): executes **only the non-HITL tools** from the last AIMessage's tool calls, in order, and writes their `ToolMessage`s to state. If the batch also contained `human.ask_clarification`, it routes to `hitl`; otherwise back to `agent`.
- **`hitl` node**: collects **ALL** HITL tool calls from the batch (the "one ask_clarification per turn" tool-description contract is advisory, not enforceable — if the model emits several, every `tool_use` id still needs a `tool_result` or the API 400s and wedges the thread). It builds the union question payload, makes exactly one call of consequence — `answers = interrupt(payload)` — then writes one `ToolMessage` per HITL `tool_call_id`. On resume, only this node re-executes — the sibling tools' results were already committed by the `tools` node in the prior super-step, and the ToolMessage writes sit *after* `interrupt()`, so they commit exactly once. **No tool re-runs, no LLM replay.**

**Mixed-batch correctness** (review objection #2): the tool-description contract ("emit ALL clarifications in ONE call") prevents multiple ask_clarification calls but NOT mixed batches like `[grep_repo, ask_clarification]`. The split above handles mixed batches structurally: side-effecting siblings commit before the interrupt. Anthropic requires every `tool_use` id to have a `tool_result` in the **next user turn**; this design produces consecutive `ToolMessage`s across two nodes, which `langchain-anthropic` merges into a single user turn. **Phase 2 exit gate includes an integration test for exactly this**: mixed batch → pause → resume → assert the next API request carries all `tool_result` blocks in one user message (and that Bedrock Converse does the same).

### 2.2 SQS integration (unchanged transport, new detection)

```
consumer receives SQS message
  → idempotency guards (§2.3)
  → async for event in graph.astream(input, config={"configurable": {"thread_id": ...}},
                                      stream_mode=["messages", "updates"]):
        map to Redis pub/sub events (§2.4)
  → final state contains "__interrupt__"?
      YES → write projection row to task_specialist_state (questions, round_id, role)
            → publish hitl event → ACK message            (same observable behavior as today)
      NO  → synthesize AgentResult → persist artifact → ACK
```

```
POST /api/chat/tasks/{id}/respond            (API contract unchanged)
  → enqueue resume message {task_id, role, round_id, answers}
  → consumer: guards (§2.3) → graph.astream(Command(resume={interrupt_id: answers}), config={same thread_id})
    (astream, not ainvoke — the resume leg of a live UI session must stream too; §2.4)
```

### 2.3 Idempotency under at-least-once delivery (review objection #3; amended after round 2)

Today's guards are load-bearing and are **retained, not replaced**. The graph checkpointer adds a subtlety: "graph completed" becomes durable at the final checkpoint, *before* the consumer's post-processing (AgentResult synthesis, artifact persist, status write, completion marker). The guards must distinguish those states. On every delivery, resolve in order:

1. **Marker present** (`orchestrator_completed_at`, `consumer.py:903`): fully processed → idempotent-replay path exactly as today (`consumer.py:912-931` — synthesize minimal result, continue through status handling). Never silently drop.
2. **Marker absent, graph complete** (`aget_state(config)` → empty `next`, no pending interrupt): the run finished but the consumer crashed before post-processing. **Skip invoke; re-run post-processing from the final checkpoint's state values** (messages, cost counters), write the marker, ACK. Dropping the message here would permanently strand a finished graph with no persisted result.
3. **Graph in progress** (non-empty `next`, no pending interrupt — crash between super-steps): re-enter with **`input=None`** to resume from the last checkpoint. Never re-pass the original input — the messages reducer would append a duplicate and fork the conversation.
4. **Resume message**: the `round_id` guard stays (`consumer.py:1339-1354`) — mismatch with the projection row → no-op ACK. `aget_state` must show a pending interrupt; none → drop. The projection row stores the **`interrupt_id`** alongside `round_id`/`thread_scheme`, and resumes use the dict form `Command(resume={interrupt_id: answers})` — a bare resume value is only unambiguous with exactly one pending interrupt, and storing the id now keeps the scheme correct if specialists ever parallelize.
5. **Fresh thread**: normal invoke with the input.

Net effect: redelivery after a crash resumes from the last checkpoint rather than re-running the specialist from scratch (today's behavior before the pause point) — but only with guards 2 and 3 as specified; the naive "completed → drop" version loses results.

### 2.4 Streaming + event taxonomy (review objection #6)

The Thinking-stream WebSocket consumes Redis pub/sub events (`task:{id}:events`) emitted today from inside `BaseAgent.run`: `streaming_chunk` (buffered 0.2s/500 chars), `tool_call_start/end`, `iteration`, `prompt_sent`, `phase_change`. Mapping:

| Today's event | LangGraph source |
|---|---|
| `streaming_chunk` | `stream_mode="messages"` token deltas, re-buffered with the same 0.2s/500-char flush in the consumer wrapper |
| `tool_call_start` / `tool_call_end` | `stream_mode="updates"` — `tools`/`hitl` node update entries |
| `iteration` | a counter in graph state incremented by the `agent` node (super-steps don't map 1:1; the state counter preserves today's numbering for the UI) |
| `prompt_sent`, `phase_change` | emitted by the consumer wrapper at invoke boundaries, unchanged |

The §2.2 flow uses `astream`, not `ainvoke`, for any agent with a live UI session (same condition that enables streaming today).

### 2.5 Loop-gate behaviors (review objection #5)

`recursion_limit = 2 × claude_max_tool_iterations + 1` maps the *count*, but three *behaviors* need explicit graph mappings:

1. **Budget ceiling** (`claude_max_cost_per_task_usd`, checked per iteration today): the `agent` node begins with a budget check against accumulated cost in state (fed by the usage callback). Over budget → route to `synthesis` (below) with a budget-exceeded system note, mirroring `budget_exceeded_on_entry`.
2. **User stop/pause** (`_should_continue`, `base.py:728-737`): same pre-model check in the `agent` node, reading the Redis control flag; stop → route to END with `stop_reason="stopped"` semantics preserved in the AgentResult adapter. **Pause** keeps today's semantics: the same pre-model Redis poll loop inside the `agent` node (worker-holding, exactly as `base.py:730-740` holds today).
3. **Forced synthesis on exhaustion** (`client.py:202-246` — today the loop executes pending tools then makes ONE no-tools call so callers never get an empty result): use the `RemainingSteps` managed value; when remaining steps can't fit another agent+tools round-trip, route to a `synthesis` node that makes a single no-tools model call. `GraphRecursionError` must never surface to the consumer in normal operation.

### 2.6 Structured output decision table (review objection #7)

`with_structured_output(method="function_calling")` forces `tool_choice`, which the Anthropic API **rejects when extended thinking is enabled**. Therefore:

| Agent class | Thinking? | Provider | Structured-output method |
|---|---|---|---|
| Specialists emitting verdict JSON | yes (16k budget) | both | **Keep the JSON-repair loop** (≤2 rounds, per `rules/review-loop-limits.md`) as a `repair` node looping back to `agent` with the parse error |
| Tier classifier (fast model, no thinking) | no | direct | `with_structured_output(method="json_schema")` — requires `langchain-anthropic>=1.1.0` |
| Tier classifier on Bedrock | no | Bedrock | Keep repair loop until `json_schema` support on Converse is verified in Phase 1 shadow |
| CodingAgent free-text phases | n/a | both | No structured output — unchanged |

`with_structured_output` raises on parse failure; it does NOT reproduce repair-then-escalate. Where the table says "repair loop", the repair node is the migration of `MAX_JSON_REPAIR_ROUNDS`, not a new design.

## 3. Orchestrator as a StateGraph (Phase 3)

```
START → classify_tier → plan → [router]
  router reads plan.full_sequence(); emits next specialist node or END
  each specialist = a compiled subgraph (the §2.1 graph) mounted DIRECTLY as a node
    (never wrapped in a function that also does other work — a wrapper re-executes
     in full when a subgraph interrupt resumes)
  after each specialist subgraph: a dedicated persist_artifact node
    (ArtifactRepository.upsert — idempotent; node-level commit means a completed
     persist node never re-executes on later interrupts)
  edges after persist:
    verdict == "block"                   → halt → END
    verdict == "error" AND strict        → halt → END
    verdict == "error" AND lenient       → emit fallback metric, drop from fan-out state, continue
    else                                 → router (next specialist or END)
  ux_designer guarded by should_run_ux_designer(stack_analyst artifact)
```

**Thread-ID topology change at Phase 3** (review objection #4): in Phase 2, specialists run standalone on `thread_id = f"{task_id}:{role}"`. In Phase 3 they become subgraphs sharing the **orchestrator's** thread (`thread_id = task_id`); a HITL interrupt inside the BA subgraph surfaces on the orchestrator thread. Consequences, explicitly in scope for Phase 3:

- The resume message schema keeps `{task_id, role, round_id}` but the consumer's thread resolution changes: orchestrator-path resumes target `thread_id = task_id`; coding-path resumes (still legacy in Phase 3) keep their old format. The projection table row records which scheme its round belongs to (`thread_scheme: standalone|orchestrator`).
- The HITL-resume "skip already-completed specialists" replay loop in `orchestrate()` is **deleted** — the checkpointer resumes mid-graph natively. Artifact preload survives only as a safety net for crash-recovery of pre-migration tasks.
- State schema (TypedDict): `task_id, ticket, repo, tier, plan, artifacts, halted_on, fallback_activated, team_settings, total_tokens, iteration counters` (per-specialist counters live in subgraph state).

Shadow mode: unchanged — the wrapper around the graph returns metadata; producer/consumer wiring decides whether it gates.

## 4. LangSmith

- `LANGSMITH_TRACING=true` + API key via env (`rules/security.md`: no hardcoding). Project per environment.
- Coexists with DataDog: DataDog keeps infra/APM + billing metrics; LangSmith owns prompt/response traces, per-node token breakdowns, eval datasets. `evaluation/` gains a LangSmith-backed eval path; existing eval tables stay.
- **PII gate before prod**: `hide_inputs`/`hide_outputs` masking hooks for ticket payloads (fintech PII), and an explicit data-residency decision (LangSmith EU or self-hosted) — LangSmith is env-flagged off in prod until that decision is signed off.

## 5. Migration phases (each independently shippable, shadow-verified)

**Corrected premise** (review objection #1): CodingAgent is **async-HITL by default today** (`coding/agent.py:909,1003`; poll at `:990` is a legacy fallback; `config.py:220 hitl_coding_enabled=True`; tasks with `triggered_by == "jira_transition"` stay on the legacy path and are covered by the same role-keyed split). Therefore old-format `AwaitingHumanInput` pauses from CodingAgent will be live throughout Phases 2–3, and the consumer runs **dual resume formats keyed by role** for the whole transition — not just a drain window. `AwaitingHumanInput` is deleted in **Phase 4** (not Phase 3).

**Phase 0 — Tracing only (1–2 days).** Add `langsmith`; wrap existing `ClaudeClient` calls with `@traceable`. Zero behavior change.

**Phase 1 — Model layer swap (behind adapter).** New deps: `langchain-core`, `langchain-anthropic` (≥1.1.0), `langchain-aws`. `LangChainProvider` satisfies the existing `ClaudeProvider` interface; config-flagged (`claude_provider_impl: legacy|langchain`).
Adapter requirements from review: (a) **thinking-block signature round-trip** — legacy `ThinkingBlock` has no signature field (`provider.py:49-50`); the loop survives today only by replaying `raw_content` (`client.py:164`). The adapter must carry the provider-native raw content (or LangChain message objects) end-to-end, or multi-turn tool use under thinking breaks. (b) Bedrock: pass `read_timeout=300` botocore Config (`provider.py:377-388`, commit 9b2423f) via `ChatBedrockConverse`; port the dotted-tool-name shim; **verify IAM allows the Converse API** (today's policy may only grant InvokeModel, `provider.py:315-317`).
**Exit gate:** 1 week shadow parity on the dogfood harness — token usage, cost (Bedrock multiplier), thinking blocks, cache hit rates, streaming deltas, refusal stop_reason propagation, on BOTH providers.

**Phase 2 — Specialist agent loop → LangGraph graph (§2).** Deps: `langgraph`, `langgraph-checkpoint-postgres`, **`psycopg[binary,pool]`** — the checkpointer requires psycopg v3 while the app is asyncpg/SQLAlchemy; this is a second Postgres driver and a **second connection pool** (size it and budget RDS connections explicitly). Checkpointer tables via `AsyncPostgresSaver.setup()` at deploy — NOT alembic-managed; exclude from alembic autogenerate (wrapping library DDL in alembic drifts on upgrades).
Specialists only (orchestrator-dispatched roles). CodingAgent stays legacy. Consumer: `__interrupt__` detection for specialist roles, `AwaitingHumanInput` catch for coding role (dual format, keyed by role).
**Exit gates:** mixed-batch HITL integration test (§2.1) green on both providers; pause/resume round-trip with zero LLM-replay verified by call-count assertions; loop-gate behaviors (§2.5) covered by tests; streaming taxonomy verified against a live UI session.

**Phase 3 — Orchestrator StateGraph (§3).** Port `orchestrate()`; thread-topology change as specified.
**Parity gate, calibrated** (review objection #10): first run the **legacy orchestrator twice** on the dogfood corpus (`backend/scripts/phase2_*`) at pinned temperature/model to measure baseline self-agreement; set the new-vs-old gate relative to that baseline (e.g., within 2 points of self-parity), N≥50 tasks, plus zero HITL resume failures. A fixed "95%" is meaningless without the nondeterminism baseline.

**Phase 4 — CodingAgent + reviewer migration; delete legacy.** Remove `run_agent_loop`, `AwaitingHumanInput`, poll-mode HITL, custom provider, dual-format resume. `task_specialist_state` shrinks to the API projection table. Checkpoint TTL cleanup added to `background/reapers.py` (delete thread checkpoints on task terminal state).

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| `langchain-anthropic` lags Anthropic API features | Raw `anthropic` SDK stays a dep; per-call `model_kwargs` passthrough; pin + test on upgrade |
| Bedrock Converse gaps (json_schema structured output unverified) | Phase 1 shadow gate checks Bedrock explicitly; §2.6 keeps repair loop on Bedrock until verified |
| Checkpointer table growth | TTL reaper (Phase 4); checkpoints deleted on task terminal state |
| Mixed tool batch + interrupt | Structural fix (§2.1) + dedicated integration test as a Phase 2 exit gate |
| SQS duplicate delivery → double graph invocation | §2.3 guards retained + `aget_state` checks; never invoke into a completed thread |
| Two Postgres drivers (asyncpg + psycopg) | Second pool sized explicitly; RDS connection budget reviewed in Phase 2 |
| LangSmith egress of fintech PII | Masking hooks + residency decision gate before prod enablement |
| Two frameworks during transition | Phases gated and short; legacy deleted in Phase 4 |
| `GraphRecursionError` replacing graceful synthesis | §2.5 `RemainingSteps` → synthesis node; error must never reach the consumer in normal operation |

## 7. Unverified-until-gated library claims

These claims are consistent with documentation but could not be verified against the installed packages (none are installed yet). Each is pinned to a phase exit gate rather than assumed:

1. `langchain-anthropic` merges consecutive `ToolMessage`s into a single user turn with all `tool_result` blocks → **Phase 2 mixed-batch integration test** (both providers).
2. `ChatBedrockConverse` accepts a botocore `Config` for `read_timeout=300` → **Phase 1** Bedrock smoke.
3. `with_structured_output(method="json_schema")` minimum version (`langchain-anthropic>=1.1.0`) and its Bedrock Converse availability → **Phase 1 shadow gate** (§2.6 keeps the repair loop wherever unverified).

## 8. Out of scope

- Frontend changes (chat API contract for HITL questions/responses unchanged).
- RAG module (`rag/`) — may adopt LangChain retrievers later; not required here.
- Replacing DataDog.
