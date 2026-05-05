# Context Management

Context window limits, server-side compaction, context editing (clear tool_uses / clear thinking), prompt caching (5m / 1h, breakpoints, invalidation), and token counting.

## Contents

- [Context windows](#context-windows)
- [Token counting](#token-counting)
- [Prompt caching](#prompt-caching)
- [Compaction](#compaction)
- [Context editing](#context-editing)
- [Sources](#sources)

## Context windows

| Model | Context | Max output |
|---|---|---|
| Opus 4.7 / 4.6, Sonnet 4.6 | 1M | 64k (128k with streaming) |
| Sonnet 4.5 | 200k | 64k |
| Haiku 4.5 | 200k | 64k |

Quirks:
- Thinking blocks from **prior** turns are auto-stripped from input — only the current turn's thinking counts.
- Inside a tool-use loop, you MUST return thinking blocks verbatim on the follow-up (cryptographic `signature` on each block).
- Newer models return 400 when input exceeds context rather than silently truncating — use `count_tokens` to check ahead.
- Max 600 images / PDF pages per request (100 on 200k models).
- Sonnet 4.6 / 4.5 / Haiku 4.5 see a `<budget:token_budget>` system warning near the limit — they self-regulate.

## Token counting

```python
count = client.messages.count_tokens(
    model="claude-opus-4-7",
    system="You are a scientist",
    tools=[{"name": "get_weather", "input_schema": {...}}],
    messages=[{"role": "user", "content": "Hello"}],
)
print(count.input_tokens)
```

- Free endpoint, separate rate limit (100-8000 RPM by tier).
- Estimate only — actual may differ slightly.
- Doesn't consume prompt cache even with `cache_control`.
- Server-tool token counts apply to first sampling call only.
- With `context_management`, shows `input_tokens` (post-edit) and `context_management.original_input_tokens`.

## Prompt caching

Cache a prompt prefix for 5 min (default) or 1 hour. Writes cost 1.25× (5m) / 2× (1h) base input; reads 0.1×. **Break-even at ~2 reuses.**

Two modes:
- **Explicit breakpoints:** up to 4 `cache_control: {"type": "ephemeral"}` markers on individual blocks.
- **Automatic:** one top-level breakpoint that caches up to the last cacheable block and moves forward as the conversation grows.

```python
response = client.messages.create(
    model="claude-opus-4-7", max_tokens=1024,
    system=[{"type": "text", "text": LONG_SYSTEM_PROMPT,
             "cache_control": {"type": "ephemeral"}}],
    tools=[
        {"name": "t1", "input_schema": {...}},
        {"name": "t2", "input_schema": {...},
         "cache_control": {"type": "ephemeral"}},  # caches both tools + system
    ],
    messages=[{"role": "user", "content": "..."}],
)
print(response.usage.cache_creation_input_tokens)  # on write
print(response.usage.cache_read_input_tokens)      # on hit
print(response.usage.input_tokens)                 # non-cached portion
```

### Invariants

- **Cache processing order:** `tools → system → messages`. Any change at an earlier stage invalidates it **and everything after**.
- **Byte-identical** prefix required — one whitespace change invalidates.
- Min cacheable length: **1024 tokens** (Opus / Sonnet), **2048 tokens** (Haiku).
- Max **4 explicit breakpoints**. Automatic mode uses 1.
- Cache is **per-model, per-org** — switching `speed="fast"` on 4.6 also breaks it.

### 1-hour cache

```python
"cache_control": {"type": "ephemeral", "ttl": "1h"}
```

Requires beta header `extended-cache-ttl-2025-04-11`. Use for big document-QA prefixes that are queried infrequently but repeatedly within the hour.

### What invalidates the cache

| Change | Invalidates |
|---|---|
| Any tool definition edit | Everything (tools → system → messages) |
| Toggling `web_search` / citations | System + messages |
| `tool_choice` / `disable_parallel_tool_use` flip | Messages only |
| Image toggle | Messages |
| `thinking` param change | Messages |
| Fast-mode toggle | Everything |

### Caching patterns

```python
# Cache a stable system prompt
system = [{"type": "text", "text": SYSTEM, "cache_control": {"type": "ephemeral"}}]

# Cache growing conversation at the last user message
messages[-1] = {"role": "user", "content": [
    {"type": "text", "text": last_user_text,
     "cache_control": {"type": "ephemeral"}},
]}
```

See [tool-use.md#prompt-caching-with-tools](tool-use.md#prompt-caching-with-tools) for tool-specific caching.

## Compaction

Server-side conversation summarization when input exceeds a threshold. Recommended default for long chats / agents. Beta header `compact-2026-01-12`. Models: Opus 4.7 / 4.6, Sonnet 4.6.

```python
response = client.beta.messages.create(
    betas=["compact-2026-01-12"],
    model="claude-opus-4-7", max_tokens=4096,
    messages=messages,
    context_management={"edits": [{
        "type": "compact_20260112",
        "trigger": {"type": "input_tokens", "value": 150000},  # default 150k, min 50k
        "pause_after_compaction": False,
        # "instructions": "Custom summary prompt...",  # replaces default entirely
    }]},
)
messages.append({"role": "assistant", "content": response.content})
```

Gotchas:
- **Append the full response (including the `compaction` block)** to messages — the API auto-drops pre-compaction content on the next turn.
- Compaction iteration is billed separately; sum `usage.iterations[]` for true cost. Top-level token counts exclude it.
- Same model is used for the summary (no cheaper option).
- Put `cache_control` on the system prompt so the cache survives compaction.
- When `pause_after_compaction=True` and `stop_reason == "compaction"`, append and re-call to continue.
- Streaming: compaction arrives in one delta, not incremental.

## Context editing

Surgical clearing strategies. Beta: `context-management-2025-06-27`.

```python
response = client.beta.messages.create(
    model="claude-opus-4-7", max_tokens=4096,
    betas=["context-management-2025-06-27"],
    messages=messages, tools=tools,
    context_management={"edits": [
        # Must be FIRST when combining with clear_tool_uses
        {"type": "clear_thinking_20251015",
         "keep": {"type": "thinking_turns", "value": 2}},
        {"type": "clear_tool_uses_20250919",
         "trigger": {"type": "input_tokens", "value": 30000},
         "keep": {"type": "tool_uses", "value": 3},
         "clear_at_least": {"type": "input_tokens", "value": 5000},
         "exclude_tools": ["web_search"],
         "clear_tool_inputs": False},
    ]},
)
# Response includes context_management.applied_edits with stats
```

Gotchas:
- Edits run server-side; client keeps the full history.
- `clear_thinking` **must be first** in the `edits` array when combined with others.
- Clearing tool_results **invalidates the cache** at the clear point. Use `clear_at_least` so the clear is worth the cache break.
- Keeping thinking blocks preserves cache; clearing invalidates.
- Pair with the memory tool so Claude persists important data before clears.

## Sources

- `../docs/context-management/context-windows.md`
- `../docs/context-management/compaction.md`
- `../docs/context-management/context-editing.md`
- `../docs/context-management/prompt-caching.md`
- `../docs/context-management/token-counting.md`
