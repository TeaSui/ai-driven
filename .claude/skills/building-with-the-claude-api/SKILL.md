---
name: building-with-the-claude-api
description: Use when writing or debugging Python code that calls the Claude API through the Anthropic Python SDK — messages.create requests, multi-turn conversations, system prompts, temperature, streaming, structured JSON output (output_config.format with json_schema on 4.6/4.7; prefill + stop_sequences on 4.5 and earlier), tool use (tool_use / tool_result blocks, multi-turn tool loops, fine-grained streaming, built-in text-editor / web-search / code-execution tools), extended thinking, vision, PDF input, citations, prompt caching with cache_control (5m default or 1h TTL), Files API, RAG pipelines (chunking, VoyageAI embeddings, BM25 lexical search, multi-index hybrid retrieval with reciprocal rank fusion), prompt engineering techniques (clarity, specificity, XML tags, one/multi-shot examples), eval workflows (datasets, model-based grading, code-based grading), MCP client/server integration including resources and prompts, or agent vs workflow design (parallelization, chaining, routing, evaluator-optimizer, composable tools, environment inspection). Covers the flat Obsidian-graph curriculum in docs/ (messages-api, model-capabilities, tools, tool-infrastructure, context-management, files, skills, mcp, third-party-platforms, managed-agents, best-practices, admin). Not for Claude Code CLI usage, Computer Use, or general agent frameworks.
---

# Building with the Claude API

Navigator for Python developers using the Anthropic SDK. Load the reference file that matches the task — do not load all of them.

## When to use

- Writing Python code that imports `anthropic` or calls `client.messages.create(...)`.
- Debugging Claude API issues: message shape, tool loops, streaming events, cache misses, stop_reason handling, citation spans, RAG relevance, MCP handshakes.
- Designing a new Claude integration (tool use, RAG pipeline, MCP server/client, Agent Skills, eval harness).

## When NOT to use

- Claude Code CLI usage, hooks, slash commands, or IDE extension questions — separate skill.
- Non-Python SDKs (TypeScript, Go, Ruby). Patterns translate, but examples here are Python.
- Building generic agent frameworks (LangChain, LlamaIndex) — those abstractions hide the primitives this skill covers.

## Always-load minimums

```python
from anthropic import Anthropic

client = Anthropic()  # reads ANTHROPIC_API_KEY from env

response = client.messages.create(
    model="claude-opus-4-7",  # or claude-sonnet-4-6, claude-haiku-4-5
    max_tokens=1024,
    messages=[{"role": "user", "content": "Hello"}],
)
print(response.content[0].text)
```

Core invariants every call respects:
- `max_tokens` is **required**.
- `messages` is a list of `{"role": "user" | "assistant", "content": str | list[block]}` with strict user/assistant alternation.
- The API is **stateless** — resend the full conversation each request.
- For pure-text responses, text is at `response.content[0].text`. For tool use / citations / thinking, iterate `response.content` and branch on `block.type`.
- **Always check `response.stop_reason`** — don't assume `end_turn`. See [references/basics.md](references/basics.md) for the full handling table.
- Never call the API from client code — originate requests from a server with `ANTHROPIC_API_KEY` in env.

## Model quick-reference

| Model ID | Best for | Notes |
|---|---|---|
| `claude-opus-4-7` | Hard reasoning, agentic coding | **Adaptive thinking required** (manual `thinking.type="enabled"` returns 400); start effort at `xhigh` |
| `claude-sonnet-4-6` | Balanced quality/cost; most apps | Default `effort="high"` — set explicitly to avoid surprise latency |
| `claude-haiku-4-5` | Cheap/fast: classification, graders, bulk | 200k context; drops on low-resource languages |
| `claude-opus-4-6` | Only path to Fast mode | 6× price when `speed="fast"` |

**Prefill is deprecated on Opus 4.7 / Opus 4.6 / Sonnet 4.6 and Mythos Preview** — sending an assistant-prefix message returns 400. Use `output_config.format` (see [references/model-capabilities.md](references/model-capabilities.md#structured-outputs)) for guaranteed JSON. Prefill still works on Sonnet 4.5 and Haiku 4.5.

## Navigation

Route to the reference for the task. Each file is self-contained.

### Foundations
| Task | File |
|---|---|
| Setup, messages, system, temperature, streaming, stop_reason handling (end_turn, max_tokens, tool_use, pause_turn, refusal, model_context_window_exceeded) | [references/basics.md](references/basics.md) |
| Clarity/directness, specificity (guidelines + steps), XML structure, one/multi-shot examples, iterative prompt evolution | [references/prompt-engineering.md](references/prompt-engineering.md) |
| Eval pipelines: dataset gen with Haiku, model-based grading (strengths/weaknesses/reasoning/score), code-based grading (syntax validators), score combination | [references/evaluation.md](references/evaluation.md) |

### Model capabilities
| Task | File |
|---|---|
| Adaptive vs manual thinking, effort levels (low → max), task budgets, structured outputs (json_schema + strict tools), citations, search_result blocks, batch API (50%), fast mode, streaming, streaming refusals, multilingual, embeddings (Voyage) | [references/model-capabilities.md](references/model-capabilities.md) |

### Tool use (client-side + built-in)
| Task | File |
|---|---|
| Custom tools, schemas, tool_use / tool_result handling, multi-turn loops, parallel tool calls, `strict: true`, Tool Runner SDK, prompt caching with tools, troubleshooting | [references/tool-use.md](references/tool-use.md) |
| Server tools (web_search, web_fetch, code_execution, advisor) and Anthropic-schema client tools (memory, bash, text_editor, computer_use) | [references/built-in-tools.md](references/built-in-tools.md) |
| Scaling tools: tool search (regex/BM25), programmatic tool calling, fine-grained streaming, tool combinations, managing tool context | [references/tool-infrastructure.md](references/tool-infrastructure.md) |

### Context and files
| Task | File |
|---|---|
| Context windows (1M/200k), server-side compaction, context editing (clear_tool_uses / clear_thinking), prompt caching (5m / 1h, breakpoints, invalidation), token counting | [references/context-management.md](references/context-management.md) |
| Files API (upload, reference by file_id), PDF input, images/vision (base64 / URL / file_id) | [references/files.md](references/files.md) |

### Higher-level primitives
| Task | File |
|---|---|
| Agent Skills: pre-built (pptx/xlsx/docx/pdf), custom upload, authoring best practices, enterprise governance | [references/agent-skills.md](references/agent-skills.md) |
| MCP connector (remote servers from Messages API), MCP client/server architecture, resources vs tools vs prompts, FastMCP, Inspector | [references/mcp.md](references/mcp.md) |
| RAG overview, chunking strategies, Voyage embeddings + cosine, BM25, multi-index hybrid retrieval via Reciprocal Rank Fusion, injecting chunks | [references/rag.md](references/rag.md) |
| Workflow vs agent, parallelization / chaining / routing / evaluator-optimizer, composable agent tools, environment inspection loop | [references/agents-workflows.md](references/agents-workflows.md) |
| Depth addenda ported from prior curriculum: temperature distribution intuition; eval rubric checklist + prefill instrumentation; embedding normalization, BM25 4-step walkthrough, worked RRF example, Voyage setup | [references/additional-notes.md](references/additional-notes.md) |

## Top pitfalls

- **Always branch on `stop_reason`.** `end_turn` is normal, but `tool_use`, `pause_turn` (server-tool iteration limit), `max_tokens`, `refusal` (reset context), and `model_context_window_exceeded` each need distinct handling. See basics.md.
- **Append the full `content` list for assistant turns with tool_use** — not `response.content[0].text`. Dropping it loses the `tool_use.id` the next `tool_result` must reference.
- **`tool_result` blocks come FIRST in the next user message** — text after, not before. Violating the ordering returns 400 `"tool_use ids were found without tool_result blocks immediately after"`.
- **Batch parallel tool results into one user message.** Splitting across turns teaches Claude to stop parallelizing.
- **Prefill doesn't work on Opus 4.7 / 4.6 / Sonnet 4.6.** Use structured outputs (`output_config.format` with `json_schema`) or `strict: true` on tools.
- **Cache invalidates on any byte change** in the prefix. Processing order: `tools → system → messages`. Changing tools kills everything. Min 1024 tokens (2048 Haiku), max 4 breakpoints, 5m or 1h TTL.
- **Structured outputs and citations are mutually exclusive** — enabling both returns 400.
- **Opus 4.7 rejects manual thinking.** Use `thinking={"type": "adaptive"}` + `output_config={"effort": "xhigh"}`. Thinking blocks with `display: "omitted"` (the 4.7 default) emit nothing visible but are still billed.
- **Fine-grained tool streaming disables JSON validation.** Handle partial/malformed JSON in `input_json_delta` application code.
- **Code execution has no internet.** Move data in/out via the Files API (`container_upload` blocks in, `code_execution_output` out).
- **MCP connector ≠ MCP client you build.** The connector is a Messages-API beta that reaches remote MCP servers directly — only `tool_use` supported, no resources/prompts.
- **Default to a workflow, not an agent.** Reach for an agent only when you genuinely can't enumerate steps, and pair every action tool with an observation tool.

## Sources

Bundled curriculum at `docs/` as a flat Obsidian graph — start at `docs/index.md` (Home MOC). Sections: `messages-api/`, `model-capabilities/`, `tools/`, `tool-infrastructure/`, `context-management/`, `files/`, `skills/`, `mcp/`, `third-party-platforms/`, `managed-agents/`, `best-practices/`, `admin/`. Each section has its own `index.md` MOC listing every page with a one-line hook. Canonical pages (Files, Skills, MCP) are single-source; Managed Agents cross-links to them. Each reference file here lists the specific docs it distills.
