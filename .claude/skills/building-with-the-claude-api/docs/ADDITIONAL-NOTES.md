# Additional notes (from prior curriculum)

Supplementary explanations ported from the earlier numbered-lesson curriculum. Each section identifies the target file it augments.

---

## D1 · `1. Building with Claude/2. Using the Messages API.md`

### The five-step request flow

Every interaction with Claude follows a predictable lifecycle:

1. **Request to server** — Your client (web/mobile) sends the user's message to your own server. Never originate requests from client code; the API key must stay server-side.
2. **Request to Anthropic API** — Your server calls `client.messages.create(...)` with the API key, model, messages, and `max_tokens`.
3. **Model processing** — Anthropic processes the request (see D2 below for the internal stages).
4. **Response to server** — The API returns a structured `Message` with `content`, `usage`, and `stop_reason`.
5. **Response to client** — Your server relays the generated text to the client UI.

### `max_tokens` is a safety limit, not a target

`max_tokens` caps how many tokens Claude is allowed to generate — it is **not** a goal Claude tries to reach. Claude writes what it thinks is appropriate and stops there. If it naturally needs fewer tokens than the limit, it simply ends early. If it hits the limit mid-thought, `stop_reason` will be `max_tokens` and the last content block may be incomplete — handle that explicitly.

---

## D2 · `1. Building with Claude/1. Features overview.md`

### Inside Claude's processing

Once Anthropic receives a request, Claude processes it through four stages:

1. **Tokenization** — Input text is broken into tokens (whole words, word fragments, spaces, symbols). Roughly one token per English word.
2. **Embedding** — Each token is converted into a long numerical vector that captures all possible meanings of that token. Polysemous words (e.g. "quantum" as physics term vs. computing term vs. "extremely small") get a single embedding that encodes all senses.
3. **Contextualization** — Each token's embedding is refined based on surrounding tokens so that the intended sense dominates. This is how Claude disambiguates meaning.
4. **Generation** — Contextualized embeddings pass through an output layer that produces a probability distribution over next tokens. Sampling (influenced by temperature) selects one, the token is appended, and the loop repeats until a stop condition fires.

Understanding this pipeline helps when reasoning about why certain prompt structures work: clearer context at earlier positions reshapes later contextualization, which changes the generation distribution.

---

## D3 · `2. Model capabilities/8. Streaming Messages.md`

### Stream event types at a glance

When streaming is enabled, the API emits a sequence of typed events:

| Event | Meaning |
|---|---|
| `message_start` | A new message is beginning. Contains the initial `Message` envelope (no content yet). `stop_reason` is `null` here. |
| `content_block_start` | A new content block (text, tool_use, thinking, citation) is opening. |
| `content_block_delta` | Incremental content for the current block. Delta types: `text_delta`, `input_json_delta`, `thinking_delta`, `signature_delta`, `citations_delta`. These are the events you forward to the UI for token-by-token rendering. |
| `content_block_stop` | The current block has closed. |
| `message_delta` | Message-level updates (final `stop_reason`, cumulative `usage`). |
| `message_stop` | End of the stream. |

Only `content_block_delta` events carry display text. Everything else is metadata.

---

## D6 · `3. Tool/4. Define tools.md`

### Python type-safety with `ToolParam`

When authoring tool schemas in Python, the Anthropic SDK ships a typed helper so your definitions are checked at import time rather than failing at request time:

```python
from anthropic.types import ToolParam

get_weather: ToolParam = {
    "name": "get_weather",
    "description": "Look up the current weather for a given city.",
    "input_schema": {
        "type": "object",
        "properties": {
            "city": {"type": "string", "description": "City name, e.g. 'Hanoi'"},
        },
        "required": ["city"],
    },
}
```

Pairing this with `mypy` / `pyright` catches common mistakes (missing `input_schema`, wrong property shape) before the API rejects the request.

### Defensive tool-function patterns

Whatever the tool runner invokes will be called with arguments Claude assembled — not always what you expect. Three practical rules:

1. **Validate inputs even if the schema says they're required.** Claude occasionally emits `undefined` or empty strings for required fields, especially under fine-grained tool streaming (which disables JSON validation).
2. **Return structured error strings, not exceptions.** Raising inside a tool breaks the agentic loop. Instead, return `{"error": "...", "detail": "..."}` as the `tool_result` content — Claude will read it and either retry or apologize to the user.
3. **Keep side effects idempotent where possible.** Multi-turn loops sometimes re-issue the same `tool_use` after a transient failure; a non-idempotent write can duplicate work.

---

## D7 · `3. Tool/5. Handle tool calls.md`

### Helper-function refactor for multi-block messages

Early tool-use tutorials typically define bare helpers like `add_user_message(messages, "text")`. Once Claude starts returning multi-block assistant turns (text + tool_use, or text + thinking + tool_use), those helpers need to accept block lists, not just strings:

```python
def add_user_message(messages, content):
    # Accept either a plain string or a pre-built block list (tool_results, etc.)
    messages.append({"role": "user", "content": content})

def add_assistant_message(messages, content):
    # Content is usually the full response.content list, not response.content[0].text
    messages.append({"role": "assistant", "content": content})

def text_from_message(message):
    # Extract the first text block from a multi-block response, or "" if none
    for block in message.content:
        if block.type == "text":
            return block.text
    return ""
```

Key invariant: when the assistant turn contains `tool_use` / `thinking` / `citations`, append `response.content` (the list) — **not** `response.content[0].text`. Dropping the list loses the `tool_use.id` that the next `tool_result` must reference, and the follow-up request will fail with `400 "tool_use ids were found without tool_result blocks immediately after"`.
