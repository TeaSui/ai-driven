# Tool Use

Custom tools, schemas, tool_use / tool_result lifecycle, multi-turn loops, parallel calls, `strict: true`, the Tool Runner SDK, prompt caching with tools, and troubleshooting. For **built-in tools** (web_search, code_execution, bash, memory, text_editor, computer_use, advisor), see [built-in-tools.md](built-in-tools.md). For **scaling tools** (tool search, programmatic tool calling, fine-grained streaming), see [tool-infrastructure.md](tool-infrastructure.md).

## Contents

- [Mental model](#mental-model)
- [Tool schemas](#tool-schemas)
- [Handling tool_use](#handling-tool_use)
- [Sending tool_result back](#sending-tool_result-back)
- [Multi-turn loop (manual)](#multi-turn-loop-manual)
- [Tool Runner SDK](#tool-runner-sdk)
- [Parallel tool use](#parallel-tool-use)
- [Strict tool use](#strict-tool-use)
- [Tool choice](#tool-choice)
- [Prompt caching with tools](#prompt-caching-with-tools)
- [Troubleshooting](#troubleshooting)
- [Sources](#sources)

## Mental model

Four-step dance:
1. Send `tools=[...]` schemas.
2. Claude may respond with `tool_use` block (`stop_reason: "tool_use"`).
3. **You** execute the tool locally and return a `tool_result` block. Claude never runs your code.
4. Claude incorporates the result and either calls another tool or produces a final text answer.

Loop ends when `stop_reason != "tool_use"`.

Tools split by execution location:
- **Client tools** — you execute: user-defined + Anthropic-schema (`bash`, `text_editor`, `computer`, `memory`).
- **Server tools** — Anthropic executes: `web_search`, `web_fetch`, `code_execution`, `advisor`, `tool_search`.

See [built-in-tools.md](built-in-tools.md) for both categories' built-ins.

## Tool schemas

Required: `name` (regex `^[a-zA-Z0-9_-]{1,64}$`), `description`, `input_schema` (JSON Schema).
Optional: `input_examples`, `cache_control`, `strict`, `defer_loading`, `allowed_callers`.

```python
tools = [{
    "name": "get_stock_price",
    "description": (
        "Returns the current trade price in USD for a ticker on NYSE/NASDAQ. "
        "Use when the user asks about a stock's current or recent price. "
        "Does NOT return company fundamentals, historical data, or news."
    ),
    "input_schema": {
        "type": "object",
        "properties": {"ticker": {"type": "string", "description": "e.g. AAPL"}},
        "required": ["ticker"],
    },
    "input_examples": [{"ticker": "AAPL"}, {"ticker": "MSFT"}],
}]
```

The **description is the selector**. 3-4+ sentences covering what / when / params / caveats beats a one-liner. Consolidate related ops via an `action` discriminator rather than shipping many near-duplicate tools.

## Handling tool_use

```python
response = client.messages.create(
    model="claude-opus-4-7", max_tokens=1024, messages=messages, tools=tools,
)
if response.stop_reason == "tool_use":
    # CRITICAL: append the full content list, not response.content[0].text
    messages.append({"role": "assistant", "content": response.content})
```

The assistant message may also contain `text` blocks (reasoning) before the `tool_use`. Iterate `response.content` and branch on `block.type`.

## Sending tool_result back

```python
import json

tool_results = []
for block in response.content:
    if block.type != "tool_use":
        continue
    try:
        output = run_tool(block.name, block.input)
        tool_results.append({
            "type": "tool_result",
            "tool_use_id": block.id,
            "content": json.dumps(output),
        })
    except Exception as e:
        tool_results.append({
            "type": "tool_result",
            "tool_use_id": block.id,
            "content": f"Error: {e}. Retry with a valid ticker.",
            "is_error": True,
        })

messages.append({"role": "user", "content": tool_results})
```

**Invariants:**
- `tool_use_id` must equal the `tool_use.id` from the assistant message exactly.
- `tool_result` blocks go in a **user** message.
- `tool_result` blocks come FIRST in the `content` array. Any `text` block BEFORE `tool_result` returns 400: `"tool_use ids were found without tool_result blocks immediately after"`.
- Each `tool_use` must have a matching `tool_result` in the very next user message.
- `is_error: true` errors are recoverable — write instructive messages (`"Rate limited, retry in 60s"`, not `"failed"`).
- `content` can be a string OR a list of text/image/document blocks (for multimodal tool results).

## Multi-turn loop (manual)

```python
def run_conversation(messages, tools, max_iterations=10):
    for _ in range(max_iterations):
        response = client.messages.create(
            model="claude-opus-4-7", max_tokens=2048, messages=messages, tools=tools,
        )
        messages.append({"role": "assistant", "content": response.content})

        if response.stop_reason == "pause_turn":
            continue  # server-tool iteration limit — resend as-is
        if response.stop_reason != "tool_use":
            return response  # end_turn, max_tokens, refusal, etc.

        tool_results = [execute_tool_block(b) for b in response.content if b.type == "tool_use"]
        messages.append({"role": "user", "content": tool_results})
    raise RuntimeError("Agentic loop exceeded max iterations")
```

Bound iterations. Log every tool_use/tool_result pair for debugging.

## Tool Runner SDK

Beta SDK helper that drives the loop for you. Use unless you need human-in-the-loop approval, custom logging, or conditional execution.

```python
from anthropic import Anthropic, beta_tool
client = Anthropic()

@beta_tool
def get_weather(location: str, unit: str = "fahrenheit") -> str:
    """Get current weather.
    Args:
        location: City and state, e.g. "San Francisco, CA"
        unit: "celsius" or "fahrenheit"
    """
    import json
    return json.dumps({"temperature": "20C", "condition": "Sunny"})

runner = client.beta.messages.tool_runner(
    model="claude-opus-4-7", max_tokens=2048,
    tools=[get_weather],
    messages=[{"role": "user", "content": "Weather in Paris?"}],
)
final = runner.until_done()
# Or iterate: for msg in runner: ...
```

Gotchas:
- Tools must return a **string or content block / list**. `json.dumps` dicts; `str()` numbers.
- Use `@beta_async_tool` + `async def` with `AsyncAnthropic`.
- Tool exceptions are caught and returned to Claude with `is_error=True`. Set `ANTHROPIC_LOG=debug` to see stack traces.
- Use `runner.generate_tool_call_response()` to intercept / modify results (e.g., add `cache_control`) before Claude sees them.
- Streaming via `stream=True` yields a `BetaMessageStream` per iteration.

## Parallel tool use

Claude may emit multiple `tool_use` blocks in one assistant turn. Execute all and batch **all** results into ONE user message:

```python
tool_uses = [b for b in response.content if b.type == "tool_use"]
tool_results = [
    {"type": "tool_result", "tool_use_id": tu.id, "content": run(tu)}
    for tu in tool_uses
]
messages += [
    {"role": "assistant", "content": response.content},
    {"role": "user", "content": tool_results},  # SINGLE message
]
```

Splitting results across separate user messages teaches Claude to stop parallelizing — it adapts fast. Encourage more parallelism in your system prompt:

```text
<use_parallel_tool_calls>
For maximum efficiency, invoke all relevant tools simultaneously rather than sequentially
when the calls are independent.
</use_parallel_tool_calls>
```

Disable parallelism with `tool_choice={"type": "auto", "disable_parallel_tool_use": True}` (invalidates message cache — set early, not mid-conversation).

## Strict tool use

`"strict": true` on a tool applies grammar-constrained sampling — the tool name is always valid and the input **always** matches your schema (no coerced types, no missing required fields, no invented keys).

```python
tools = [{
    "name": "search_flights",
    "description": "Search flights by destination and date.",
    "strict": True,
    "input_schema": {
        "type": "object",
        "properties": {
            "destination":    {"type": "string"},
            "departure_date": {"type": "string", "format": "date"},
            "passengers":     {"type": "integer", "enum": [1,2,3,4,5,6,7,8,9]},
        },
        "required": ["destination", "departure_date"],
        "additionalProperties": False,
    },
}]
```

Rules:
- Must set `additionalProperties: false` and list required fields.
- `pattern` JSON Schema keyword is **not supported** under strict.
- Pair with `tool_choice: {"type": "any"}` to guarantee BOTH that a tool fires AND the input conforms.
- HIPAA-eligible; never put PHI in schema property names / enum / const — schemas are cached separately for up to 24h.

Incompatible with: [programmatic tool calling](tool-infrastructure.md#programmatic-tool-calling), `disable_parallel_tool_use`.

## Tool choice

```python
tool_choice = {"type": "auto"}                # default; Claude picks or answers directly
tool_choice = {"type": "any"}                 # MUST call some tool
tool_choice = {"type": "tool", "name": "x"}   # force a specific tool
tool_choice = {"type": "none"}                # prohibit tools this turn
```

- `any` / `tool` prefill the assistant with the tool call, suppressing natural-language preamble.
- Extended thinking is **incompatible** with `any` / `tool` — only `auto` / `none`.
- Changing `tool_choice` invalidates message cache.

## Prompt caching with tools

Place `cache_control: {"type": "ephemeral"}` on the LAST tool to cache the whole tool-definitions prefix. Processing order is `tools → system → messages` — changes at one level invalidate it **and everything after**.

```python
tools = [
    {"name": "get_weather", "description": "...", "input_schema": {...}},
    {"name": "get_time",    "description": "...", "input_schema": {...},
     "cache_control": {"type": "ephemeral"}},  # caches BOTH tools
]
```

Invalidation reference:
- Any tool-definition edit → invalidates everything.
- Toggling web_search / citations → invalidates system + messages.
- Changing `tool_choice` / `disable_parallel_tool_use` → invalidates messages only.
- Toggling images / thinking params → invalidates messages.

Use `defer_loading: true` + Tool Search tool to add tools mid-conversation without breaking the prefix cache (see [tool-infrastructure.md](tool-infrastructure.md#tool-search)).

See [context-management.md#prompt-caching](context-management.md#prompt-caching) for breakpoint limits, TTLs, and usage metrics.

## Troubleshooting

| Symptom | Cause / Fix |
|---|---|
| 400: `"tool_use ids were found without tool_result blocks immediately after"` | `tool_result` not FIRST in next user message, or missing. Put all `tool_result` blocks first; text may follow. |
| `"tool_use_id not found"` | You appended `response.content[0].text` instead of the full list. Preserve `response.content` in history. |
| Claude invents tool params | Add `"strict": true`. Tighten `input_schema` — set `additionalProperties: false`, use `enum` where possible. |
| Claude picks the wrong tool | Sharpen descriptions by WHEN not just WHAT. Check name collisions. Add `input_examples`. |
| Claude stops calling tools in parallel | You split results across multiple user messages. Batch into one. |
| Cache misses on tool changes | Avoid mid-conversation tool edits; use `defer_loading` + tool search. Keep `tool_choice` stable or put breakpoint before its variation. |
| `"string patterns are not supported with strict"` | Drop `pattern` from schema OR drop `strict`. |
| Opus 4.6+ — JSON escape weirdness | Parse with `json.loads()`, never raw-string match on serialized input. |
| `"All tools have defer_loading: true"` | The tool_search tool itself must not have `defer_loading`; at least one loadable tool required. |

## Sources

- `../docs/tools/tool-use-overview.md` through `../docs/tools/parallel-tool-use.md` (concepts through parallel)
- `../docs/tools/tool-runner-sdk.md`
- `../docs/tools/strict-tool-use.md`
- `../docs/tools/tool-use-with-prompt-caching.md`
- `../docs/tools/troubleshooting-tool-use.md`
