# Advanced Features

Extended thinking, vision, PDF input, citations, prompt caching, and code execution + Files API.

## Contents

- [Extended thinking](#extended-thinking)
- [Vision (images)](#vision-images)
- [PDF input](#pdf-input)
- [Citations](#citations)
- [Prompt caching](#prompt-caching)
- [Code execution + Files API](#code-execution--files-api)
- [Gotchas](#gotchas)
- [Sources](#sources)

## Extended thinking

Gives Claude a separate "thinking" budget before the visible answer — useful for hard reasoning, math, multi-step analysis.

```python
response = client.messages.create(
    model=model,
    max_tokens=8000,
    thinking={"type": "enabled", "budget": 4000},  # thinking tokens
    messages=[{"role": "user", "content": "Prove that sqrt(2) is irrational."}],
)

for block in response.content:
    if block.type == "thinking":
        print("THINKING:", block.thinking)
    elif block.type == "text":
        print("ANSWER:", block.text)
```

- Minimum budget: **1024 tokens**.
- Budget must be < `max_tokens` (leave room for the final answer).
- `thinking` blocks count toward output tokens and are billed.
- Supported on Opus 4.x and Sonnet 4.x family (confirm model support).
- Preserve `thinking` blocks in `messages` history alongside `text` blocks when continuing a conversation — stripping them can degrade follow-up reasoning.

Use when: math, planning, multi-hop reasoning, tricky code review. Skip for: quick lookups, chat, simple classification.

## Vision (images)

Attach images as base64 blocks in a user message.

```python
import base64, httpx

image_data = base64.standard_b64encode(httpx.get(image_url).content).decode("utf-8")

response = client.messages.create(
    model=model,
    max_tokens=1000,
    messages=[{
        "role": "user",
        "content": [
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": "image/jpeg",
                    "data": image_data,
                },
            },
            {"type": "text", "text": "What's in this image?"},
        ],
    }],
)
```

Limits and costs:
- Token cost ≈ `(width × height) / 750`.
- Max 100 images per request.
- Max 5 MB per image.
- Max 8000×8000 px for single-image requests; 2000×2000 px for multi-image requests.
- Supported formats: JPEG, PNG, GIF, WebP.

Prefer URL-referenced images (`"type": "url"`) when the image is publicly hosted — skips the base64 overhead.

## PDF input

PDFs use a `document` block. Claude reads both text and visual elements (tables, figures).

```python
with open("report.pdf", "rb") as f:
    pdf_data = base64.standard_b64encode(f.read()).decode("utf-8")

response = client.messages.create(
    model=model,
    max_tokens=1000,
    messages=[{
        "role": "user",
        "content": [
            {
                "type": "document",
                "source": {
                    "type": "base64",
                    "media_type": "application/pdf",
                    "data": pdf_data,
                },
            },
            {"type": "text", "text": "Summarize the key findings."},
        ],
    }],
)
```

Limits:
- Max 32 MB per PDF.
- Max 100 pages per PDF.
- Scanned PDFs work — Claude reads them visually.

For multi-document chat, upload once via the Files API and reference by ID instead of re-base64-encoding each turn.

## Citations

Make Claude cite exact passages from a source document. Add `title` and `citations: {"enabled": True}` to the document block.

```python
response = client.messages.create(
    model=model,
    max_tokens=1000,
    messages=[{
        "role": "user",
        "content": [
            {
                "type": "document",
                "source": {
                    "type": "base64",
                    "media_type": "application/pdf",
                    "data": pdf_data,
                },
                "title": "earth.pdf",
                "citations": {"enabled": True},
            },
            {"type": "text", "text": "What is Earth's circumference?"},
        ],
    }],
)

for block in response.content:
    if block.type == "text":
        print(block.text)
        if getattr(block, "citations", None):
            for cite in block.citations:
                print(f"  ↳ {cite.cited_text!r} "
                      f"(doc {cite.document_index}: {cite.document_title}, "
                      f"pages {cite.start_page_number}-{cite.end_page_number})")
```

Each citation exposes:
- `cited_text` — the verbatim passage
- `document_index` — which document in the message
- `document_title` — the title you supplied
- `start_page_number`, `end_page_number` — page range (PDF)

Citations work with PDFs, plain text documents, and custom-content documents. They're ideal for compliance, research, or legal applications where source-grounding is required.

## Prompt caching

Reuse preprocessing work across requests. First request writes the cache; follow-ups read from it at reduced cost and latency.

Core rules:
- **Opt-in via `cache_control`.** Nothing is cached automatically.
- **Minimum 1024 tokens** to be cacheable.
- **Max 4 breakpoints** per request.
- **TTL: 1 hour** (ephemeral).
- Processing order: **tools → system → messages**. A breakpoint caches everything up to and including itself, in that order.
- **Byte-exact match required.** A single character change invalidates everything from that breakpoint onward.

### Caching tool schemas

Mutate copies — don't touch the caller's tool list.

```python
if tools:
    tools_clone = [t.copy() for t in tools]
    tools_clone[-1]["cache_control"] = {"type": "ephemeral"}
    params["tools"] = tools_clone
```

### Caching system prompts

Convert the string system prompt into a structured text block.

```python
if system:
    params["system"] = [{
        "type": "text",
        "text": system,
        "cache_control": {"type": "ephemeral"},
    }]
```

### Caching conversation history

Put a breakpoint on the last *user* message whose content will be reused.

```python
messages[-1] = {
    "role": "user",
    "content": [
        {"type": "text", "text": user_text, "cache_control": {"type": "ephemeral"}},
    ],
}
```

### Reading the cache in usage metrics

```python
print(response.usage.cache_creation_input_tokens)  # on write
print(response.usage.cache_read_input_tokens)      # on hit
print(response.usage.input_tokens)                 # non-cached portion
```

First call shows `cache_creation_*`. Subsequent calls within the hour show `cache_read_*`. If content changed, you'll see a partial read + a new write.

### Where caching pays off

- Long system prompts (3K+ tokens) that don't vary.
- Large tool schemas across repeated requests.
- Document-QA flows where the same document is queried multiple times.
- Multi-turn agents with stable early context.

Not worth it when: content changes every request, or content is <1024 tokens.

## Code execution + Files API

Claude can execute Python in a sandboxed Docker container — no network, ephemeral filesystem. Data flows in and out via the Files API.

### Upload a file

```python
from anthropic import Anthropic

client = Anthropic()

file_metadata = client.beta.files.upload(
    file=("data.csv", open("data.csv", "rb")),
)
# file_metadata.id is what you reference in messages
```

### Run code against uploaded data

```python
messages = [{
    "role": "user",
    "content": [
        {"type": "text", "text": "Analyze churn drivers. Include at least one plot."},
        {"type": "container_upload", "file_id": file_metadata.id},
    ],
}]

response = client.messages.create(
    model=model,
    max_tokens=4000,
    messages=messages,
    tools=[{"type": "code_execution_20250522", "name": "code_execution"}],
)
```

### Handle the response

Claude emits multiple block types during code execution:
- `text` — Claude's analysis and prose.
- `server_tool_use` (code execution) — the Python Claude ran.
- `code_execution_tool_result` — the output (stdout, returncode, generated files).
- `code_execution_output` — file IDs of artifacts (plots, reports).

```python
for block in response.content:
    if block.type == "code_execution_output":
        # Download the artifact
        file_id = block.file_id
        content = client.beta.files.download(file_id=file_id).read()
        with open(f"{file_id}.png", "wb") as f:
            f.write(content)
```

Claude may execute code multiple times per turn, iterating on its analysis.

### Environment constraints

- **No network access** — can't hit external APIs or install arbitrary packages from the internet.
- Standard scientific stack available: pandas, numpy, matplotlib, seaborn, scikit-learn, scipy.
- Filesystem is ephemeral — persist outputs via `code_execution_output` → Files API download.
- Timeout per execution; Claude retries within the turn if needed.

## Gotchas

- **Thinking budget must be strictly less than max_tokens.** The remaining `max_tokens − budget` is what Claude has left for the visible answer.
- **Strip base64 images from message history after use** — they're retransmitted every turn otherwise, ballooning input costs. Uploading via the Files API and referencing by ID is cheaper for multi-turn.
- **Citations only work when you *explicitly* enable them.** The document-title field is what appears in citation metadata.
- **Cache-control `ephemeral` is the only type today.** The `type` key exists to allow future cache tiers.
- **Cache processing order quirk:** if you change tools but keep system prompt the same, the system-prompt cache is also invalidated (because tools come first in processing order).
- **`cache_creation_input_tokens` costs ~25% more than normal input tokens**, but `cache_read_input_tokens` costs ~10%. The break-even is roughly 2 reuses.
- **Code execution container has no `pip install` from the internet.** If you need a non-stdlib package, check availability before assuming.
- **PDFs with corrupted or DRM-locked content will fail silently** — the response may claim the PDF was empty. Verify your PDF renders in a viewer first.

## Sources

- `docs/building-with-the-claude-api/38-extended-thinking.md`
- `docs/building-with-the-claude-api/39-image-support.md`
- `docs/building-with-the-claude-api/40-PDF-support.md`
- `docs/building-with-the-claude-api/41-citations.md`
- `docs/building-with-the-claude-api/42-prompt-caching.md`
- `docs/building-with-the-claude-api/43-rules-of-prompt-caching.md`
- `docs/building-with-the-claude-api/44-prompt-caching-in-action.md`
- `docs/building-with-the-claude-api/45-code-execution-and-the-files-API.md`
