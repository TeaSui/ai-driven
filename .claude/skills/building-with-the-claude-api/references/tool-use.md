# Tool Use

Custom tools, schemas, `tool_use` / `tool_result` blocks, multi-turn tool loops, fine-grained streaming, and built-in tools (text-editor, web-search).

## Contents

- [Mental model](#mental-model)
- [Tool functions](#tool-functions)
- [Tool schemas](#tool-schemas)
- [Handling tool_use blocks in responses](#handling-tool_use-blocks-in-responses)
- [Sending tool_result blocks back](#sending-tool_result-blocks-back)
- [Multi-turn tool loops](#multi-turn-tool-loops)
- [Multiple tools](#multiple-tools)
- [Fine-grained tool streaming](#fine-grained-tool-streaming)
- [Built-in: text editor tool](#built-in-text-editor-tool)
- [Built-in: web search tool](#built-in-web-search-tool)
- [Gotchas](#gotchas)
- [Sources](#sources)

## Mental model

Tool use is a four-step dance:
1. You send a request with a `tools=[...]` list of schemas.
2. Claude may respond with a `tool_use` block naming a tool and its input.
3. **You** execute the tool locally and send back a `tool_result` block (Claude never runs your code).
4. Claude incorporates the result and either calls another tool or produces a final text answer.

The loop ends when `response.stop_reason != "tool_use"`.

## Tool functions

Write normal Python functions. Handle validation explicitly — Claude will occasionally pass empty strings or malformed args.

```python
from datetime import datetime

def get_current_datetime(date_format="%Y-%m-%d %H:%M:%S"):
    if not date_format:
        raise ValueError("date_format cannot be empty")
    return datetime.now().strftime(date_format)
```

The function's job is to accept the `input` dict Claude provides and return something JSON-serializable (string, dict, list). You'll `json.dumps` it before sending as a `tool_result`.

## Tool schemas

A schema tells Claude what the tool does, what it takes, and when to use it. Use JSON Schema for `input_schema`.

```python
get_current_datetime_schema = {
    "name": "get_current_datetime",
    "description": "Returns the current date and time, formatted using a strftime string.",
    "input_schema": {
        "type": "object",
        "properties": {
            "date_format": {
                "type": "string",
                "description": "Python strftime format string, e.g. '%Y-%m-%d'.",
            }
        },
        "required": ["date_format"],
    },
}
```

Tips:
- Name tools with verbs: `get_`, `create_`, `list_`, `search_`.
- The **description is the trigger**. Say exactly when to use the tool and what it returns. This is what Claude reads to decide.
- Mark genuinely required params in `required`. Optional params belong in `properties` but not in `required`.
- For typed Python support, `from anthropic.types import ToolParam` — it typechecks the schema dict.

## Handling tool_use blocks in responses

When Claude calls a tool, `stop_reason == "tool_use"` and `response.content` contains at least one block of `type == "tool_use"`. The response may also include `text` blocks (reasoning before the tool call).

```python
response = client.messages.create(
    model=model,
    max_tokens=1000,
    messages=messages,
    tools=[get_current_datetime_schema],
)

if response.stop_reason == "tool_use":
    # Preserve the entire content list — don't lose text blocks or tool_use IDs
    messages.append({"role": "assistant", "content": response.content})
```

**Critical:** append `response.content` (the list), not `response.content[0].text`. You need the `tool_use` block with its `id` preserved in history so the follow-up `tool_result` can reference it.

## Sending tool_result blocks back

For each `tool_use` block, produce a matching `tool_result` block. The `tool_use_id` on the result must equal the `id` on the `tool_use`.

```python
import json

tool_results = []
for block in response.content:
    if block.type == "tool_use":
        try:
            output = run_tool(block.name, block.input)
            tool_results.append({
                "type": "tool_result",
                "tool_use_id": block.id,
                "content": json.dumps(output),
                "is_error": False,
            })
        except Exception as e:
            tool_results.append({
                "type": "tool_result",
                "tool_use_id": block.id,
                "content": f"Error: {e}",
                "is_error": True,
            })

messages.append({"role": "user", "content": tool_results})
```

`tool_result` blocks go in a **user** message — they are the user's reply to Claude's tool-call request.

## Multi-turn tool loops

Wrap the request/tool/result cycle in a loop until Claude stops calling tools:

```python
def run_tool(tool_name, tool_input):
    if tool_name == "get_current_datetime":
        return get_current_datetime(**tool_input)
    elif tool_name == "add_duration_to_datetime":
        return add_duration_to_datetime(**tool_input)
    elif tool_name == "set_reminder":
        return set_reminder(**tool_input)
    raise ValueError(f"Unknown tool: {tool_name}")

def run_tools(response):
    results = []
    for block in response.content:
        if block.type != "tool_use":
            continue
        try:
            output = run_tool(block.name, block.input)
            results.append({
                "type": "tool_result",
                "tool_use_id": block.id,
                "content": json.dumps(output),
                "is_error": False,
            })
        except Exception as e:
            results.append({
                "type": "tool_result",
                "tool_use_id": block.id,
                "content": f"Error: {e}",
                "is_error": True,
            })
    return results

def run_conversation(messages, tools):
    while True:
        response = client.messages.create(
            model=model,
            max_tokens=1000,
            messages=messages,
            tools=tools,
        )
        messages.append({"role": "assistant", "content": response.content})

        if response.stop_reason != "tool_use":
            return response  # Final text answer

        tool_results = run_tools(response)
        messages.append({"role": "user", "content": tool_results})
```

Safety rails you may want:
- Max-iteration cap (e.g., 10) to prevent runaway loops.
- Timeout per tool call.
- Structured logging of every `tool_use` / `tool_result` pair for debugging.

## Multiple tools

Pass multiple schemas in the `tools=[...]` list. Route to the right function by `block.name`:

```python
response = client.messages.create(
    model=model,
    max_tokens=1000,
    messages=messages,
    tools=[
        get_current_datetime_schema,
        add_duration_to_datetime_schema,
        set_reminder_schema,
    ],
)
```

Claude can emit multiple `tool_use` blocks in a single response (parallel tool calls) when the tasks are independent. Execute them all before replying with the batched `tool_result` blocks.

## Fine-grained tool streaming

When streaming with tools, the API buffers tool-argument JSON until a top-level key-value pair is complete and schema-valid, then releases a burst. This creates visible stalls.

Default streaming:

```python
with client.messages.stream(...) as stream:
    for chunk in stream:
        if chunk.type == "input_json":
            print(chunk.partial_json, end="")  # incremental
            current_args = chunk.snapshot  # cumulative
```

If you need raw, unvalidated chunks for a snappier UI:

```python
# Fine-grained tool calling — JSON validation disabled
run_conversation(messages, tools=[save_article_schema], fine_grained=True)
```

With `fine_grained=True`:
- Chunks arrive as fast as Claude generates them.
- **No JSON validation** — you must handle malformed JSON yourself.
- `json.loads(chunk.snapshot)` can raise; wrap it in try/except and tolerate partial states.

Use it only when streaming latency genuinely hurts UX.

## Built-in: text editor tool

Claude-maintained tool for inspecting and editing files. You provide the **execution**; Claude provides the schema.

Model-specific tool type:
- Claude 3.7 Sonnet / 3.5 Haiku+: `{"type": "text_editor_20250124", "name": "str_replace_editor"}`
- Claude 3.5 Sonnet: `{"type": "text_editor_20241022", "name": "str_replace_editor"}`

```python
tools = [{"type": "text_editor_20250124", "name": "str_replace_editor"}]

response = client.messages.create(
    model=model,
    max_tokens=1000,
    messages=messages,
    tools=tools,
)
```

Operations Claude can request via `tool_use.input.command`:
- `view` — read a file or directory
- `create` — create a new file
- `str_replace` — replace a string in a file
- `insert` — insert at a line
- `undo_edit` — revert the last edit

You implement each command against the local filesystem and return the result as a `tool_result`.

## Built-in: web search tool

Server-side tool — **Claude runs the web search**, you don't implement anything. You just enable it.

```python
tools = [{
    "type": "web_search_20250305",
    "name": "web_search",
    "max_uses": 5,                          # cap searches per request
    "allowed_domains": ["anthropic.com"],   # optional allowlist
    # "blocked_domains": ["spam.example"],  # or blocklist (not both)
}]

response = client.messages.create(
    model=model,
    max_tokens=2000,
    messages=[{"role": "user", "content": "What did Anthropic release this week?"}],
    tools=tools,
)
```

Response `content` will include `server_tool_use` blocks (Claude's search queries) and `web_search_tool_result` blocks (results). Claude iterates automatically.

Pricing: billed per search invocation; use `max_uses` to cap cost.

## Gotchas

- **`tool_use_id` mismatch = hard error.** The `tool_result.tool_use_id` must exactly match the `tool_use.id` from the prior assistant message.
- **Append full `response.content`, not `.text`.** You'll lose the `tool_use` block and the next round will fail with "tool_use_id not found."
- **Claude never executes your code.** It emits a JSON request — you run the function locally.
- **Tool descriptions drive selection.** A vague description causes tool-confusion. Be concrete: what the tool does, what it returns, and when to use it.
- **Always include both `is_error=True` and a textual error message** in the `tool_result` when a tool throws. Claude can recover (retry with different args) if told what failed.
- **Parallel tool calls** — multiple `tool_use` blocks in one response — execute them all before the next turn.
- **Fine-grained streaming disables validation.** Handle malformed JSON; don't assume every snapshot parses.
- **Built-in `text_editor` tool type is model-specific.** Pick the right date suffix (`_20250124` vs `_20241022`) for your model.
- **`web_search` results count toward input tokens on subsequent turns** — the search results become part of the conversation history.

## Sources

- `docs/building-with-the-claude-api/20-introducing-tool-use.md`
- `docs/building-with-the-claude-api/21-project-overview.md`
- `docs/building-with-the-claude-api/22-tool-functions.md`
- `docs/building-with-the-claude-api/23-tool-schemas.md`
- `docs/building-with-the-claude-api/24-handling-message-blocks.md`
- `docs/building-with-the-claude-api/25-sending-tool-results.md`
- `docs/building-with-the-claude-api/26-multi-turn-conversations-with-tools.md`
- `docs/building-with-the-claude-api/27-implementing-multiple-turns.md`
- `docs/building-with-the-claude-api/28-using-multiple-tools.md`
- `docs/building-with-the-claude-api/29-fine-grained-tool-calling.md`
- `docs/building-with-the-claude-api/30-the-text-edit-tool.md`
- `docs/building-with-the-claude-api/31-the-web-search-tool.md`
