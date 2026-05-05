# Built-in Tools

Server-executed tools (run by Anthropic): `web_search`, `web_fetch`, `code_execution`, `advisor`. Anthropic-schema client tools (you execute, schema is built in): `memory`, `bash`, `text_editor`, `computer`. For custom tools / lifecycle / Tool Runner, see [tool-use.md](tool-use.md).

## Contents

### Server tools
- [Web search](#web-search)
- [Web fetch](#web-fetch)
- [Code execution](#code-execution)
- [Advisor tool](#advisor-tool)

### Client Anthropic-schema tools
- [Text editor tool](#text-editor-tool)
- [Bash tool](#bash-tool)
- [Memory tool](#memory-tool)
- [Computer use](#computer-use)

- [Server-tool mechanics](#server-tool-mechanics)
- [Sources](#sources)

## Web search

Real-time web with built-in citations. $10/1000 searches + tokens. Org admin must enable in Console.

```python
response = client.messages.create(
    model="claude-opus-4-7", max_tokens=1024,
    tools=[{
        "type": "web_search_20250305", "name": "web_search",
        "max_uses": 5,
        "allowed_domains": ["example.com"],  # or blocked_domains — not both
        "user_location": {
            "type": "approximate", "city": "San Francisco",
            "region": "California", "country": "US",
            "timezone": "America/Los_Angeles",
        },
    }],
    messages=[{"role": "user", "content": "Weather in NYC?"}],
)
```

Versions:
- `web_search_20250305` — ZDR-eligible.
- `web_search_20260209` — dynamic filtering via code execution; **NOT ZDR by default**. For ZDR, set `"allowed_callers": ["direct"]` to disable filtering.

Gotchas:
- Citations ALWAYS enabled — must surface them to end users per policy.
- Preserve `encrypted_content` and `encrypted_index` across multi-turn or citations break.
- Errors return 200 with `web_search_tool_result_error` (codes: `too_many_requests`, `invalid_input`, `max_uses_exceeded`, `query_too_long`, `unavailable`).
- Multi-search turns frequently trigger `pause_turn` — resend to continue.
- Domain filters: no scheme (`example.com` not `https://example.com`), one wildcard in path only.

## Web fetch

Fetch specific URLs (text + PDF) already seen in the conversation. No extra cost beyond tokens.

```python
response = client.messages.create(
    model="claude-opus-4-7", max_tokens=1024,
    tools=[{
        "type": "web_fetch_20250910", "name": "web_fetch",
        "max_uses": 5,
        "allowed_domains": ["example.com"],
        "citations": {"enabled": True},  # OPT-IN (opposite of web_search)
        "max_content_tokens": 100000,
    }],
    messages=[{"role": "user", "content": "Analyze https://example.com/article"}],
)
```

Gotchas:
- Only fetches URLs **already in conversation context** (user messages, client tool results, prior search/fetch). NOT URLs Claude invents.
- Data-exfiltration risk — use `allowed_domains` + `max_uses` in untrusted environments.
- JS-rendered sites not supported.
- Errors include `url_not_allowed`, `unsupported_content_type` (only text/PDF), `url_too_long` (>250 chars).
- PDFs returned as base64.

## Code execution

Python/Bash in a sandboxed Linux container (Python 3.11.12, 5GiB RAM, 1 CPU, **no internet**). Data in/out via Files API.

```python
response = client.beta.messages.create(
    model="claude-opus-4-7", max_tokens=4096,
    betas=["files-api-2025-04-14"],
    tools=[{"type": "code_execution_20250825", "name": "code_execution"}],
    messages=[{"role": "user", "content": "Calculate mean of [1..10]"}],
)

# With file upload + container reuse:
file = client.beta.files.upload(file=open("data.csv", "rb"))
response = client.beta.messages.create(
    model="claude-opus-4-7", max_tokens=4096,
    betas=["files-api-2025-04-14"],
    messages=[{"role": "user", "content": [
        {"type": "text", "text": "Analyze"},
        {"type": "container_upload", "file_id": file.id},
    ]}],
    tools=[{"type": "code_execution_20250825", "name": "code_execution"}],
)
container_id = response.container.id  # reuse across requests
```

Versions:
- `code_execution_20250522` — Python only (legacy).
- `code_execution_20250825` — adds Bash + file ops.
- `code_execution_20260120` — REPL persistence + programmatic tool calling; Opus/Sonnet 4.5+ only.

Pricing: **free** when paired with `web_search_20260209` / `web_fetch_20260209`. Otherwise 1,550 free hours/org/month, then $0.05/hour/container.

Gotchas:
- NOT ZDR-eligible.
- Containers expire 30 days after creation; scoped to workspace.
- Sub-tools emit: `bash_code_execution` and `text_editor_code_execution`.
- Combining with the client `bash` tool = two separate execution environments. State is NOT shared — clarify in system prompt.
- If files are included, execution time is billed even if the tool isn't invoked.
- Output files return as `file_id` in `code_execution_output` blocks — download via Files API.

## Advisor tool

Beta: pair a fast executor model with a stronger advisor that receives the full transcript mid-generation. Single API request; advisor is a server-side sub-inference.

```python
response = client.beta.messages.create(
    model="claude-sonnet-4-6", max_tokens=4096,
    betas=["advisor-tool-2026-03-01"],
    tools=[{
        "type": "advisor_20260301", "name": "advisor",
        "model": "claude-opus-4-7",
        "max_uses": 5,
        "caching": {"type": "ephemeral", "ttl": "5m"},  # worth it at 3+ calls
    }],
    messages=[{"role": "user", "content": "Build a Go worker pool..."}],
)
```

Valid pairs: advisor ≥ executor capability (Haiku/Sonnet/Opus executors with Opus 4.7 advisor).

Gotchas:
- `max_tokens` bounds executor output only; advisor has its own budget.
- Advisor doesn't stream — expect pauses.
- No built-in conversation cap — count client-side. **When removing advisor tool from a conversation, also strip `advisor_tool_result` blocks** or get 400.
- `caching` break-even at ~3 calls. Toggling off/on mid-convo causes misses.
- `clear_thinking` with `keep != "all"` breaks advisor cache.
- Result variants: `advisor_result` (text) or `advisor_redacted_result` (encrypted) — branch on `content.type`.
- Executor/advisor token usage reported separately in `usage.iterations[]`.

## Text editor tool

Anthropic-schema client tool for viewing / editing files. Commands: `view` (optional `view_range`), `str_replace`, `create`, `insert`. ZDR-eligible.

```python
tools = [{
    "type": "text_editor_20250728",
    "name": "str_replace_based_edit_tool",
    "max_characters": 10000,  # truncate view outputs; only on _20250728+
}]

# Claude emits, you execute (1-indexed view_range; -1 = EOF):
# {"command": "view", "path": "x.py", "view_range": [1, 50]}
# {"command": "str_replace", "path": "x.py",
#  "old_str": "for num in range(2, limit + 1)",
#  "new_str": "for num in range(2, limit + 1):"}
# {"command": "create", "path": "t.py", "file_text": "..."}
# {"command": "insert", "path": "x.py", "insert_line": 0, "insert_text": "..."}
```

Gotchas:
- `old_str` must match **exactly** — whitespace / indentation included. No fuzzy matching.
- `insert_line: 0` = beginning of file.
- Older versions (`_20241022`, `_20250124`, `_20250429`) still work but differ; check model compatibility.
- Pairs naturally with `bash_20250124` — both are client-executed so state is shared.

## Bash tool

Anthropic-schema client tool. You run a persistent bash session and return stdout+stderr. +245 input tokens.

```python
tools = [{"type": "bash_20250124", "name": "bash"}]

# Claude emits: {"command": "ls *.py"} or {"restart": true}
# You execute, return:
# {"type": "tool_result", "tool_use_id": ..., "content": stdout_and_stderr}
```

Security-critical. **Run in Docker/VM**. Other hardening:
- Use an allowlist parsed via `shlex.split`; reject shell operators (`|`, `&&`, `;`, `>`).
- `subprocess.run(..., shell=False, timeout=30)` — pass an argument list, not a string.
- No interactive commands (`vim`, `less`, password prompts) — bash tool has no TTY.
- Truncate large outputs; strip AWS keys / secrets before returning.
- Log all commands for audit.

If combined with `code_execution`, clarify in system prompt that they're separate environments with no shared state.

## Memory tool

Client tool giving Claude a persistent `/memories` directory across conversations. Commands: `view`, `create`, `str_replace`, `insert`, `delete`, `rename`. **You implement storage** (files, DB, encrypted, whatever).

```python
tools = [{"type": "memory_20250818", "name": "memory"}]
# Commands arriving from Claude:
# {"command": "view",        "path": "/memories"}
# {"command": "create",      "path": "/memories/notes.txt", "file_text": "..."}
# {"command": "str_replace", "path": "...", "old_str": "...", "new_str": "..."}
# {"command": "insert",      "path": "...", "insert_line": 2, "insert_text": "..."}
```

SDK helpers: Python `BetaAbstractMemoryTool` subclass (TS `betaMemoryTool`).

Gotchas:
- **Validate paths!** Reject `../`, URL-encoded traversals (`%2e%2e%2f`), resolve to canonical paths, verify within `/memories`. Directory traversal is the #1 security risk.
- Claude auto-views `/memories` first — an instruction is injected into the system prompt.
- Cap file sizes; consider paginated reads.
- `str_replace` errors on duplicate `old_str` matches — require unique.
- Return format is strict: directory listings have specific headers; file content uses 6-char right-aligned line numbers + tab.
- Pair with compaction for long sessions — memory persists across compaction boundaries.

## Computer use

Beta. Screenshot + mouse + keyboard control, typically alongside bash + text_editor. **High prompt-injection risk.**

```python
response = client.beta.messages.create(
    model="claude-opus-4-7", max_tokens=1024,
    betas=["computer-use-2025-11-24"],
    tools=[
        {"type": "computer_20251124", "name": "computer",
         "display_width_px": 1024, "display_height_px": 768, "display_number": 1},
        {"type": "text_editor_20250728", "name": "str_replace_based_edit_tool"},
        {"type": "bash_20250124", "name": "bash"},
    ],
    messages=[{"role": "user", "content": "Save a cat picture to desktop."}],
)
```

Beta headers:
- `computer-use-2025-11-24` — Opus 4.7 / 4.6 / Sonnet 4.6 / Opus 4.5.
- `computer-use-2025-01-24` — older models.

Security stance:
- Run in a dedicated VM / container with minimal privileges.
- Webpage / image content can override instructions (prompt injection). Auto-classifiers flag suspicious screenshots; require human confirmation for consequential actions (purchases, ToS acceptance).
- Use domain allowlists.
- Never expose credentials to the agent.
- Reference implementation: `anthropic-quickstarts/computer-use-demo`.

## Server-tool mechanics

Server tools emit `server_tool_use` blocks with `srvtoolu_` IDs and their results in the **same assistant turn** — no client-side `tool_result` needed.

`pause_turn` comes from server-tool internal loops hitting their iteration cap. Handle by resending the conversation with the same tool set:

```python
while True:
    response = client.messages.create(model=model, max_tokens=4096, messages=messages, tools=tools)
    messages.append({"role": "assistant", "content": response.content})
    if response.stop_reason != "pause_turn":
        break
```

Never include a standalone `code_execution` tool alongside `_20260209` web tools — that creates two execution environments and confuses the model.

## Sources

- `../docs/tools/server-tools.md`
- `../docs/tools/web-search-tool.md`
- `../docs/tools/web-fetch-tool.md`
- `../docs/tools/code-execution-tool.md`
- `../docs/tools/advisor-tool.md`
- `../docs/tools/memory-tool.md`
- `../docs/tools/bash-tool.md`
- `../docs/tools/computer-use-tool.md`
- `../docs/tools/text-editor-tool.md`
