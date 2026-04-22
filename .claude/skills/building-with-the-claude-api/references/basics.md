# Basics

Setup, request shape, multi-turn conversations, system prompts, temperature, streaming, and structured JSON output.

## Contents

- [Setup and environment](#setup-and-environment)
- [A minimal request](#a-minimal-request)
- [Multi-turn conversations](#multi-turn-conversations)
- [System prompts](#system-prompts)
- [Temperature](#temperature)
- [Response streaming](#response-streaming)
- [Structured JSON output (prefill + stop sequences)](#structured-json-output-prefill--stop-sequences)
- [Gotchas](#gotchas)
- [Sources](#sources)

## Setup and environment

- Install: `pip install anthropic` (or `uv add anthropic`).
- API key: create in the Anthropic Console (workspace → Create Key). Copy once — the console does not redisplay it.
- Store in `.env` as `ANTHROPIC_API_KEY=sk-...`. Load with `python-dotenv` before instantiating the client.
- Never ship the key to client code. All API calls originate from a server.

```python
from dotenv import load_dotenv
from anthropic import Anthropic

load_dotenv()
client = Anthropic()  # reads ANTHROPIC_API_KEY from env
model = "claude-sonnet-4-0"
```

## A minimal request

Every request needs `model`, `max_tokens`, and `messages`. The response's `content` is a list of blocks; for a pure-text reply, the text lives at `content[0].text`.

```python
response = client.messages.create(
    model=model,
    max_tokens=1000,
    messages=[{"role": "user", "content": "What's 2+2?"}],
)
print(response.content[0].text)
```

The response also exposes `response.usage` (`input_tokens`, `output_tokens`) and `response.stop_reason` (`"end_turn"`, `"max_tokens"`, `"stop_sequence"`, `"tool_use"`).

## Multi-turn conversations

Claude is **stateless**. To continue a conversation, resend the entire history on every request. Standard helpers used throughout the curriculum:

```python
def add_user_message(messages, text):
    messages.append({"role": "user", "content": text})

def add_assistant_message(messages, text):
    messages.append({"role": "assistant", "content": text})

def chat(messages, system=None, temperature=1.0, stop_sequences=None):
    params = {
        "model": model,
        "max_tokens": 1000,
        "messages": messages,
        "temperature": temperature,
    }
    if system:
        params["system"] = system
    if stop_sequences:
        params["stop_sequences"] = stop_sequences
    response = client.messages.create(**params)
    return response.content[0].text
```

Usage:

```python
messages = []
add_user_message(messages, "Who won the 2022 World Cup?")
reply = chat(messages)
add_assistant_message(messages, reply)
add_user_message(messages, "Who won the year before?")
reply = chat(messages)
```

`messages` must alternate `user`/`assistant`. Two consecutive roles of the same type will error.

## System prompts

System prompts set persistent instructions, persona, or constraints. Pass as a top-level `system` parameter — **not** inside `messages`.

```python
response = client.messages.create(
    model=model,
    max_tokens=1000,
    system="You are a terse CLI assistant. Reply in under 40 words.",
    messages=[{"role": "user", "content": "Explain HTTP/2"}],
)
```

Put durable behavior in `system`: persona, output format, constraints, tone. Reserve `messages` for turn-by-turn content.

## Temperature

`temperature` ranges 0.0–1.0 and controls randomness in token sampling.

| Range | Use for |
|---|---|
| 0.0–0.3 | Factual answers, code generation, data extraction, content moderation |
| 0.4–0.7 | Summarization, education, problem-solving, constrained creative work |
| 0.8–1.0 | Brainstorming, open creative writing, marketing copy, idea generation |

`temperature=0` is near-deterministic (picks the highest-probability token) but **not guaranteed identical** across calls.

## Response streaming

Stream tokens as they are produced. Useful for chat UIs and long responses.

```python
with client.messages.stream(
    model=model,
    max_tokens=1000,
    messages=[{"role": "user", "content": "Write a haiku about caching."}],
) as stream:
    for text in stream.text_stream:
        print(text, end="", flush=True)

final_message = stream.get_final_message()
```

`stream.text_stream` yields incremental text chunks. After the stream closes, `stream.get_final_message()` returns a full `Message` object (same shape as a non-streamed response) with usage and the complete content.

For tool-use streaming, see [tool-use.md](tool-use.md) — you also need `InputJsonEvent` handling.

## Structured JSON output (prefill + stop sequences)

Claude has no "JSON mode" flag. To reliably get clean JSON:

1. **Prefill** the assistant turn with an opening code fence so Claude commits to JSON syntax.
2. Pass **`stop_sequences`** so generation halts at the closing fence.
3. Parse directly — the response text is the JSON payload.

```python
import json

messages = []
add_user_message(messages, "Generate 3 movie ideas as JSON with title, genre, logline.")
add_assistant_message(messages, "```json")

raw = chat(messages, stop_sequences=["```"])
data = json.loads(raw)
```

This works because:
- The `"```json"` prefill forces Claude to continue with JSON, not prose.
- The `stop_sequences=["```"]` stops generation before the closing fence so `raw` is parseable.
- Claude's `stop_reason` will be `"stop_sequence"` when this fires.

## Gotchas

- **`max_tokens` is mandatory.** Omitting it raises a request error.
- **Stateless means resend history.** There is no `conversation_id` / `thread_id` concept in the Messages API.
- **Role alternation.** Two user messages in a row will fail; merge them or insert an assistant turn.
- **`content[0].text` only works for pure-text responses.** With tool use, citations, or mixed content, iterate `response.content` and branch on `block.type`.
- **Temperature doesn't guarantee variation.** Even at 1.0, Claude may produce similar outputs for short prompts.
- **Streaming errors surface when exiting the `with` block.** Don't catch inside the loop — wrap the whole `with` statement.
- **Prefill carries through:** whatever you put in the prefilled assistant message prefixes the response. Don't prefill "Sure!" unless you want it in the output.

## Sources

- `docs/building-with-the-claude-api/1-accessing-the-api.md`
- `docs/building-with-the-claude-api/2-getting-an-api-key.md`
- `docs/building-with-the-claude-api/3-making-a-request.md`
- `docs/building-with-the-claude-api/4-multi-turn-conversations.md`
- `docs/building-with-the-claude-api/5-system-prompts.md`
- `docs/building-with-the-claude-api/6-temperature.md`
- `docs/building-with-the-claude-api/7-response-streaming.md`
- `docs/building-with-the-claude-api/8-structured-data.md`
