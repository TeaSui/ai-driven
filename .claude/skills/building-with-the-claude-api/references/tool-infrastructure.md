# Tool Infrastructure

Scaling tools beyond ~20 definitions: tool search, programmatic tool calling, fine-grained streaming, and the decision framework for combining these techniques. For custom-tool fundamentals see [tool-use.md](tool-use.md).

## Contents

- [Decision guide](#decision-guide)
- [Tool search](#tool-search)
- [Programmatic tool calling](#programmatic-tool-calling)
- [Fine-grained tool streaming](#fine-grained-tool-streaming)
- [Canonical tool combinations](#canonical-tool-combinations)
- [Sources](#sources)

## Decision guide

Context-bloat strategies are **composable**, not mutually exclusive:

| Problem | Reach for |
|---|---|
| 20+ tool definitions (tool block > 10k input tokens) | Tool search (regex or BM25) |
| Repeated agent chains with 3+ dependent tool calls | Programmatic tool calling |
| Stable tool + system prefix across requests | Prompt caching ([context-management.md](context-management.md#prompt-caching)) |
| Long conversations with stale `tool_result` clutter | Context editing (`clear_tool_uses_20250919`) or compaction |
| Large tool-argument JSON slowing UX | Fine-grained tool streaming |

Prompt caching pays back on the 2nd request (writes cost 1.25×, reads 0.1×). Don't add tool search until you actually have 20+ tools — for 10, it's overhead.

## Tool search

On-demand tool loading for catalogs up to 10,000 tools. Reduces baseline context ~85%. Claude calls the tool-search tool; matching tools are injected as `tool_reference` blocks.

```python
response = client.messages.create(
    model="claude-opus-4-7", max_tokens=2048,
    tools=[
        # The searcher itself must NOT have defer_loading.
        {"type": "tool_search_tool_bm25_20251119", "name": "tool_search"},
        # Cold tools — loaded only when search hits them.
        {"name": "get_weather",      "description": "...", "input_schema": {...}, "defer_loading": True},
        {"name": "send_email",       "description": "...", "input_schema": {...}, "defer_loading": True},
        {"name": "query_analytics",  "description": "...", "input_schema": {...}, "defer_loading": True},
        # Hot tools — keep 3-5 non-deferred for common cases.
        {"name": "search_docs",      "description": "...", "input_schema": {...}},
    ],
    messages=[{"role": "user", "content": "What's the weather in SF?"}],
)
```

Variants:
- `tool_search_tool_regex_20251119` — Python `re.search` patterns, 200-char max. Case-sensitive by default; use `(?i)`.
- `tool_search_tool_bm25_20251119` — natural-language queries, more forgiving.

Gotchas:
- Every injected `tool_reference` must have a matching tool def in `tools` — otherwise 400.
- The search tool itself with `defer_loading: true` → 400.
- Not compatible with `input_examples`.
- Use namespace prefixes (`github_`, `slack_`) for better discovery.
- Works with MCP toolsets via `defer_loading: True` on the toolset.

## Programmatic tool calling

Claude writes Python that calls your tools from inside a code-execution sandbox. Intermediate `tool_result`s never hit model context — perfect for multi-step data chains, filters, aggregations over many items.

```python
response = client.messages.create(
    model="claude-opus-4-7", max_tokens=4096,
    tools=[
        {"type": "code_execution_20260120", "name": "code_execution"},
        {"name": "query_database",
         "description": "SELECT against internal DB. Returns JSON list of rows.",
         "input_schema": {
             "type": "object",
             "properties": {"sql": {"type": "string"}}, "required": ["sql"],
         },
         "allowed_callers": ["code_execution_20260120"]},
    ],
    messages=[{"role": "user", "content": "Top-revenue region across W, E, C regions"}],
)
```

Models: Opus 4.7 / 4.6 / 4.5, Sonnet 4.6 / 4.5. Requires `code_execution_20260120`.

Gotchas:
- Tools are **async** in the sandbox — Claude writes `await my_tool(...)`.
- `tool_result` blocks returned to the sandbox must contain ONLY tool_result blocks (no text).
- Container idles out in 4.5 min, max 30 days. Reuse via `container` param.
- **Incompatible** with `strict: true`, `tool_choice`, and `disable_parallel_tool_use`.
- MCP-connector tools cannot be invoked programmatically.
- Tool results are strings — treat as untrusted input inside the sandbox.
- Document output format in tool descriptions since Claude will deserialize in code.

## Fine-grained tool streaming

Stream tool-input JSON character-by-character without buffering / validation. Use when you need sub-second TTFB for long tool arguments (long file writes, long queries).

```python
with client.messages.stream(
    model="claude-opus-4-7", max_tokens=65536,
    tools=[{
        "name": "make_file",
        "description": "Write a file with many lines",
        "eager_input_streaming": True,  # enables fine-grained
        "input_schema": {
            "type": "object",
            "properties": {
                "filename":        {"type": "string"},
                "lines_of_text":   {"type": "array", "items": {"type": "string"}},
            },
            "required": ["filename", "lines_of_text"],
        },
    }],
    messages=[{"role": "user", "content": "Write a long poem to poem.txt"}],
) as stream:
    tool_inputs = {}
    for event in stream:
        if event.type == "content_block_start" and event.content_block.type == "tool_use":
            tool_inputs[event.index] = ""
        elif event.type == "content_block_delta" and event.delta.type == "input_json_delta":
            tool_inputs[event.index] += event.delta.partial_json
```

Gotchas:
- Only on user-defined tools (not server tools).
- JSON may be invalid / truncated when `stop_reason == "max_tokens"` — handle partials.
- `content_block_start.input == {}` is a placeholder; the real input builds from `input_json_delta`.
- When returning invalid JSON to Claude for recovery, wrap it: `{"INVALID_JSON": "<raw>"}`.

## Canonical tool combinations

Common pairings worth replicating:

| Use case | Tools |
|---|---|
| Research / Q&A with sources | `web_search` + `web_fetch` (cite then fetch the 2-3 relevant hits) |
| Data analysis on upload | `code_execution` + Files API (`container_upload`) |
| Dev loop | `bash` + `text_editor` (state shared — both client-side) |
| Cross-session continuity | `memory` + any other toolset (orthogonal) |
| Desktop automation | `computer` + `bash` + `text_editor` |
| Large tool catalog | Tool search + `defer_loading: True` on cold tools |

Avoid:
- Standalone `code_execution` tool alongside `_20260209` web-search / web-fetch — creates two sandboxes that confuse the model.
- Combining `bash` client tool with `code_execution` without stating in the system prompt that they are separate environments with no shared state.

## Sources

- `../docs/tool-infrastructure/tool-reference.md`
- `../docs/tool-infrastructure/manage-tool-context.md`
- `../docs/tool-infrastructure/tool-combinations.md`
- `../docs/tool-infrastructure/tool-search-tool.md`
- `../docs/tool-infrastructure/programmatic-tool-calling.md`
- `../docs/tool-infrastructure/fine-grained-tool-streaming.md`
