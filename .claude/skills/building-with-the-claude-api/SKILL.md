---
name: building-with-the-claude-api
description: Use when writing or debugging Python code that calls the Claude API through the Anthropic Python SDK — messages.create requests, multi-turn conversations, system prompts, temperature, streaming, structured JSON output via prefill, tool use (tool_use / tool_result blocks, multi-turn tool loops, fine-grained streaming, built-in text-editor / web-search / code-execution tools), extended thinking, vision, PDF input, citations, prompt caching with cache_control ephemeral, Files API, RAG pipelines (chunking, VoyageAI embeddings, BM25 lexical search, multi-index hybrid retrieval with reciprocal rank fusion), prompt engineering techniques (clarity, specificity, XML tags, one/multi-shot examples), eval workflows (datasets, model-based grading, code-based grading), MCP client/server integration including resources and prompts, or agent vs workflow design (parallelization, chaining, routing, evaluator-optimizer, composable tools, environment inspection). Covers the 62-lesson curriculum in docs/building-with-the-claude-api/. Not for Claude Code CLI usage, Computer Use, or general agent frameworks.
---

# Building with the Claude API

Navigator for the 62-lesson curriculum at `docs/building-with-the-claude-api/` (see [docs/building-with-the-claude-api/README.md](../../../docs/building-with-the-claude-api/README.md) for the human-readable section index). Load the reference file that matches the task — do not load all of them.

## When to use

- Writing Python code that imports `anthropic` or calls `client.messages.create(...)`.
- Debugging Claude API issues: message shape, tool loops, streaming events, cache misses, citation spans, RAG relevance, MCP handshakes.
- Designing a new Claude integration (tool use, RAG pipeline, MCP server/client, eval harness).

## When NOT to use

- Claude Code CLI usage, hooks, slash commands, or IDE extension questions — separate skill.
- Computer Use / desktop automation — separate skill.
- Building generic agent frameworks, LangChain, LlamaIndex patterns — separate skill.
- Non-Python SDKs (TypeScript, Go, Ruby). Patterns translate, but examples here are Python.

## Always-load minimums

Model IDs and client setup Claude needs before writing any API code:

```python
from anthropic import Anthropic

client = Anthropic()  # reads ANTHROPIC_API_KEY from env
model = "claude-sonnet-4-6"  # latest Sonnet; swap to "claude-opus-4-7" or "claude-haiku-4-5" as needed

response = client.messages.create(
    model=model,
    max_tokens=1000,
    messages=[{"role": "user", "content": "Hello"}],
)
print(response.content[0].text)
```

Core invariants:
- `max_tokens` is **required** on every call.
- `messages` is a list of `{"role": "user" | "assistant", "content": str | list[block]}`.
- Claude is **stateless** — every request resends the full conversation history.
- Access text via `response.content[0].text` when the response is pure text. With tool use or multi-block content, iterate `response.content` and branch on `block.type`.
- Never call the API from client code — always from a server with `ANTHROPIC_API_KEY` loaded from env (e.g., `.env` + `python-dotenv`).

## Navigation

Route to the reference file for the task at hand. Read only what's needed.

| Task | File |
|---|---|
| Making requests, multi-turn, system prompts, temperature, streaming text, structured JSON via prefill + stop_sequences | [references/basics.md](references/basics.md) |
| Clarity/directness, specificity (guidelines + steps), XML tag structure, one-shot / multi-shot examples | [references/prompt-engineering.md](references/prompt-engineering.md) |
| Eval pipeline design, test-dataset generation with Haiku, model-based grading, code-based grading (syntax validators) | [references/evaluation.md](references/evaluation.md) |
| Tool functions, tool schemas, `tool_use` / `tool_result` block handling, multi-turn tool loops, multiple tools, fine-grained streaming, text-editor tool, web-search tool | [references/tool-use.md](references/tool-use.md) |
| RAG overview, chunking strategies, VoyageAI embeddings + cosine similarity, BM25 lexical search, multi-index hybrid retrieval with reciprocal rank fusion | [references/rag.md](references/rag.md) |
| Extended thinking, vision (images), PDF input, citations, prompt caching (cache_control ephemeral), code execution + Files API | [references/advanced-features.md](references/advanced-features.md) |
| MCP intro, client/server architecture, stdio/HTTP transports, implementing a client, defining resources (direct vs templated), accessing resources from a client, designing/using prompts, tools-vs-resources-vs-prompts, inspector | [references/mcp.md](references/mcp.md) |
| Workflow vs agent decision, parallelization / chaining / routing / evaluator-optimizer patterns, composable agent tools, environment-inspection loop | [references/agents-workflows.md](references/agents-workflows.md) |

## Top pitfalls

- **Stateless API.** No server-side memory. Every request resends the full `messages` list. Conversations are rebuilt client-side.
- **Append the full `content` list when recording an assistant turn with tool use** — not `response.content[0].text`. Preserving the list keeps `tool_use` blocks in the history so the follow-up `tool_result` can reference their IDs.
- **`tool_use_id` must match exactly.** The `tool_result` block's `tool_use_id` has to equal the `id` on the corresponding `tool_use` block from the previous assistant message.
- **Cache invalidates on any byte change.** Adding " please" to a system prompt, reordering a tool list, or changing whitespace discards the entire cache for that component. Cache hits require byte-identical content up to and including the breakpoint.
- **Cache minimum is 1024 tokens**, max 4 breakpoints, 1-hour TTL. Content shorter than 1024 tokens cannot be cached.
- **Cache processing order: tools → system → messages.** Breakpoints respect this order; changing an earlier segment invalidates everything after it.
- **Prefill + stop_sequences for structured output.** Add an assistant message with `` "```json" `` and pass `stop_sequences=["```"]`. Parse the response text directly with `json.loads`.
- **Model-graders default to mid-range scores.** Ask for strengths, weaknesses, AND reasoning alongside the score — otherwise the grader converges on 6/10 regardless of quality.
- **Fine-grained tool streaming disables JSON validation.** You must handle malformed JSON (e.g., `"count": undefined`) in application code when `fine_grained=True`.
- **Citation document must be a `document` block.** Wrap PDF/text in `{"type": "document", "source": {...}, "title": "...", "citations": {"enabled": True}}`. Citations appear as separate blocks in the response with `cited_text`, `document_index`, and page ranges.
- **Code execution runs in a sandboxed Docker container with no network access.** Get data in/out via the Files API (`container_upload` blocks in, `code_execution_output` blocks out).
- **MCP servers and clients are usually implemented separately** — you build one or the other, not both. Servers expose tools/resources; clients connect and call them.
- **Tools / resources / prompts have different control planes.** Tools are model-driven, resources are app-driven (e.g. user @-mention triggers `read_resource`), prompts are user-driven (slash command). Don't model GET-shaped operations as tools — use resources.
- **Default to a workflow, not an agent.** If you can write the steps down, build a workflow (parallelize / chain / route / evaluator-optimizer). Reach for an agent only when the request space is too open to enumerate, and pair every action tool with an observation tool — agents that can't see the result of their actions hallucinate the world's state.

## Sources

All references cite their source docs inline. Primary curriculum: `docs/building-with-the-claude-api/1-accessing-the-api.md` through `62-workflows-vs-agents.md` (62 lessons; one extra walkthrough at `35a-implementing-the-RAG-flow.md`). The grouped index is at `docs/building-with-the-claude-api/README.md`.
