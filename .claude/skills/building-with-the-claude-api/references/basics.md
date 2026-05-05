# Basics

Setup, request shape, multi-turn conversations, system prompts, temperature, streaming, **stop_reason handling** (the part most apps get wrong), and structured JSON output.

> See also: [additional-notes.md § S2](additional-notes.md#s2--basicsmd--temperature-intuition) — temperature intuition (how it reshapes the sampling distribution; why T=0 is near-deterministic but not bit-identical).

## Contents

- [Setup and environment](#setup-and-environment)
- [A minimal request](#a-minimal-request)
- [Multi-turn conversations](#multi-turn-conversations)
- [System prompts](#system-prompts)
- [Temperature](#temperature)
- [Response streaming](#response-streaming)
- [Handling stop_reason](#handling-stop_reason)
- [Structured JSON output](#structured-json-output)
- [Gotchas](#gotchas)
- [Sources](#sources)

## Setup and environment

- Install: `pip install anthropic` (or `uv add anthropic`).
- API key: create in the Anthropic Console. Copy once — it isn't redisplayed.
- Store in `.env` as `ANTHROPIC_API_KEY=sk-...`. Load with `python-dotenv` before instantiating the client.
- Never ship the key to client code — always server-originated requests.

```python
from dotenv import load_dotenv
from anthropic import Anthropic

load_dotenv()
client = Anthropic()  # reads ANTHROPIC_API_KEY
model = "claude-opus-4-7"  # or claude-sonnet-4-6, claude-haiku-4-5
```

## A minimal request

```python
response = client.messages.create(
    model=model,
    max_tokens=1024,
    messages=[{"role": "user", "content": "What's 2+2?"}],
)
print(response.content[0].text)
# response.usage.{input_tokens, output_tokens, cache_read_input_tokens, cache_creation_input_tokens}
# response.stop_reason: see "Handling stop_reason" below
```

## Multi-turn conversations

The API is **stateless**. Resend the full history every turn. Enforce strict user/assistant alternation — two consecutive user messages = 400 error.

```python
def add_user(messages, text):       messages.append({"role": "user", "content": text})
def add_assistant(messages, text):  messages.append({"role": "assistant", "content": text})

def chat(messages, system=None, temperature=1.0, stop_sequences=None):
    params = {"model": model, "max_tokens": 1024, "messages": messages, "temperature": temperature}
    if system: params["system"] = system
    if stop_sequences: params["stop_sequences"] = stop_sequences
    return client.messages.create(**params).content[0].text
```

When the assistant turn contains tool_use / thinking / citations, append `response.content` (the full list) — not just the text — or the follow-up turn will break.

## System prompts

Top-level `system` parameter, not inside `messages`. Put persona, output format, constraints, tone here. Use a structured block (list with `type: "text"`) when you want to add `cache_control`.

```python
response = client.messages.create(
    model=model, max_tokens=1024,
    system="You are a terse CLI assistant. Reply in under 40 words.",
    messages=[{"role": "user", "content": "Explain HTTP/2"}],
)
```

## Temperature

`0.0–1.0` range. Defaults to `1.0`.

| Range | Use for |
|---|---|
| 0.0–0.3 | Factual answers, code gen, data extraction, moderation |
| 0.4–0.7 | Summarization, education, constrained creative work |
| 0.8–1.0 | Brainstorming, open creative writing, idea generation |

`temperature=0` is near-deterministic but **not bit-identical** across calls. On thinking-enabled models, temperature has reduced effect inside the thinking block.

## Response streaming

```python
with client.messages.stream(
    model=model, max_tokens=1024,
    messages=[{"role": "user", "content": "Write a haiku about caching."}],
) as stream:
    for text in stream.text_stream:
        print(text, end="", flush=True)
    final = stream.get_final_message()  # full Message with usage and stop_reason
```

Event model (lower-level): `message_start` → (`content_block_start` → `content_block_delta*` → `content_block_stop`)+ → `message_delta` → `message_stop`. Delta types: `text_delta`, `input_json_delta`, `thinking_delta`, `signature_delta`, `citations_delta`.

- `stop_reason` is `null` in `message_start`, populated in `message_delta`.
- `usage` in `message_delta` is **cumulative**, not incremental.
- For `max_tokens` > ~60k, streaming is required to avoid HTTP timeouts.

For tool-use streaming details, see [tool-use.md](tool-use.md). For input_json_delta handling, see [tool-infrastructure.md](tool-infrastructure.md#fine-grained-tool-streaming).

## Handling stop_reason

Every successful response carries `stop_reason`. **Always branch on it** — `end_turn` is not a given.

| Value | What it means | Action |
|---|---|---|
| `end_turn` | Natural completion | Use `response.content` |
| `max_tokens` | Hit your `max_tokens` limit | Retry with higher limit (esp. if last block is incomplete `tool_use`) or ask Claude to continue |
| `stop_sequence` | Hit a `stop_sequences` entry | Check `response.stop_sequence` |
| `tool_use` | Claude wants a tool executed | Run tools, send `tool_result` back — see tool-use.md |
| `pause_turn` | Server-tool sampling loop hit its ~10 iteration cap | Resend the conversation as-is (append assistant response, re-call) |
| `refusal` | Streaming safety classifier blocked output | **Reset / remove the refused turn**; continuing keeps triggering refusals. Try Haiku 4.5 if frequent |
| `model_context_window_exceeded` | Output hit the model's context window before `max_tokens` | Valid but truncated — consider chunking input or summarizing |

```python
def dispatch(response):
    sr = response.stop_reason
    if sr == "tool_use":                         return run_tools_and_continue(response)
    if sr == "pause_turn":                       return continue_server_tool_loop(response)
    if sr == "max_tokens":                       return handle_truncation(response)
    if sr == "model_context_window_exceeded":    return handle_context_limit(response)
    if sr == "refusal":                          return handle_refusal(response)  # reset context
    # end_turn / stop_sequence
    return response.content[0].text
```

### Empty-response trap

Claude sometimes returns **empty content with `end_turn`** after tool results. Cause: you added a `text` block after a `tool_result` in the same user message, teaching Claude to expect user text after every tool use.

**Fix:** send `tool_result` blocks alone in the user message. If you still get an empty response, append a fresh `{"role": "user", "content": "Please continue"}` — don't retry with the empty response, Claude already decided it was done.

### Incomplete tool_use on max_tokens

If `stop_reason == "max_tokens"` and the last content block is `tool_use` with partial JSON, retry the same request with a larger `max_tokens`. The model had no room to finish the call.

### Pause_turn for server tools

Server tools (web_search, web_fetch, code_execution) run an internal sampling loop capped at ~10 iterations. If the loop exceeds it, you get `pause_turn`. Continue:

```python
while True:
    response = client.messages.create(model=model, max_tokens=2048, messages=messages, tools=tools)
    messages.append({"role": "assistant", "content": response.content})
    if response.stop_reason != "pause_turn":
        break
```

## Structured JSON output

Two strategies. Pick by model.

### Opus 4.7 / Opus 4.6 / Sonnet 4.6 / Mythos: structured outputs

**Prefill returns 400 on these models.** Use `output_config.format` with a JSON Schema — the decoder is grammar-constrained to your schema. GA (no beta header).

```python
response = client.messages.create(
    model="claude-opus-4-7",
    max_tokens=1024,
    messages=[{"role": "user", "content": "Extract: Alice (alice@x.com) wants a demo Tue 2pm"}],
    output_config={
        "format": {
            "type": "json_schema",
            "schema": {
                "type": "object",
                "properties": {
                    "name":  {"type": "string"},
                    "email": {"type": "string"},
                    "demo":  {"type": "boolean"},
                },
                "required": ["name", "email", "demo"],
                "additionalProperties": False,
            },
        }
    },
)
import json
data = json.loads(response.content[0].text)
```

Set `additionalProperties: false` and list every property in `required` — otherwise the model may emit extras. **Incompatible with Citations** — both together returns 400.

### Sonnet 4.5 / Haiku 4.5 / older: prefill + stop_sequences

```python
messages = [{"role": "user", "content": "Give 3 movie ideas with title, genre, logline."},
            {"role": "assistant", "content": "```json"}]
response = client.messages.create(
    model="claude-haiku-4-5",
    max_tokens=1024,
    messages=messages,
    stop_sequences=["```"],
)
data = json.loads(response.content[0].text)
```

The assistant prefix forces JSON syntax; `stop_sequences` halts before the closing fence so the text is directly parseable. `stop_reason` will be `"stop_sequence"`.

## Gotchas

- **`max_tokens` is mandatory.** Omitting errors out.
- **No `conversation_id`.** Fully stateless.
- **Role alternation enforced.** Merge or interleave — two users in a row fails.
- **`content[0].text` only for pure text.** With tool_use / thinking / citations, iterate blocks.
- **Temperature doesn't guarantee variation.** Short prompts often give similar outputs at 1.0.
- **Streaming errors surface on `__exit__`.** Wrap the whole `with`, don't try/except inside the loop.
- **Prefill is deprecated on 4.7/4.6 tier.** A prefilled assistant message → 400. Migrate to structured outputs.
- **Never add text after `tool_result` in the same user message.** Breaks future parallelism and can cause empty responses.
- **Check `stop_reason` before indexing `content[0]`.** A refused or empty response has no `[0].text`.

## Sources

- `../docs/messages-api/using-the-messages-api.md`
- `../docs/messages-api/handling-stop-reasons.md`
- `../docs/model-capabilities/streaming-messages.md`
- `../docs/model-capabilities/structured-outputs.md`
- `../docs/model-capabilities/streaming-refusals.md`
