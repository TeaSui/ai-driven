# Model Capabilities

Thinking (adaptive / manual), effort, task budgets, structured outputs, citations, search_result blocks, batch API, fast mode, embeddings (Voyage), multilingual. Pick the section for the capability you need — nothing else loads automatically.

## Contents

- [Adaptive thinking (Opus 4.7 default)](#adaptive-thinking)
- [Manual extended thinking (Sonnet 4.6 / Opus 4.6 and older)](#manual-extended-thinking)
- [Effort](#effort)
- [Task budgets](#task-budgets)
- [Structured outputs](#structured-outputs)
- [Citations](#citations)
- [Search results blocks](#search-result-blocks)
- [Batch processing](#batch-processing)
- [Fast mode](#fast-mode)
- [Embeddings (Voyage AI)](#embeddings)
- [Multilingual](#multilingual)
- [Sources](#sources)

## Adaptive thinking

Required on **Opus 4.7** (manual mode returns 400). Recommended on Opus 4.6 / Sonnet 4.6. Claude decides when and how deeply to think; you control with `effort`.

```python
response = client.messages.create(
    model="claude-opus-4-7",
    max_tokens=16000,
    thinking={"type": "adaptive", "display": "summarized"},  # 4.7 defaults to "omitted"
    output_config={"effort": "xhigh"},  # coding/agentic workloads
    messages=[{"role": "user", "content": "Refactor this module..."}],
)
for b in response.content:
    if b.type == "thinking":
        print("THINK:", b.thinking)
```

Gotchas:
- On Opus 4.7, `display` defaults to `"omitted"` (silent change from 4.6). Set `"summarized"` to see reasoning; tokens are billed either way.
- `signature` on thinking blocks must round-trip **unchanged** when you replay the assistant turn (tool-use multi-turn). Stripping it breaks reasoning and rejects follow-up requests.
- Switching between `adaptive` / `enabled` / `disabled` invalidates message-level prompt cache.
- At `high` / `max` effort, `stop_reason: "max_tokens"` is more likely — raise `max_tokens` (≥64k recommended) or lower effort.

## Manual extended thinking

For **Sonnet 4.6 / Opus 4.6 and older**. Rejected by Opus 4.7.

```python
response = client.messages.create(
    model="claude-sonnet-4-6",
    max_tokens=16000,
    thinking={"type": "enabled", "budget_tokens": 10000},  # must be < max_tokens, min 1024
    messages=[{"role": "user", "content": "Prove sqrt(2) is irrational."}],
)
```

Gotchas:
- `budget_tokens` counts toward billed output tokens even if only a summary surfaces.
- Preserve thinking blocks verbatim across turns when in a tool-use loop.
- Manual mode doesn't support interleaved thinking — use adaptive for agentic loops.

## Effort

Cross-cutting knob for total token spend (thinking + text + tool calls). GA on Opus 4.7 / 4.6 / 4.5, Sonnet 4.6, Mythos.

```python
response = client.messages.create(
    model="claude-opus-4-7", max_tokens=4096,
    output_config={"effort": "medium"},  # low | medium | high (default) | xhigh (Opus 4.7) | max
    messages=[...],
)
```

Guidance:
- Opus 4.7 agentic / coding: start at `xhigh`, pair with `max_tokens ≥ 64000`.
- Sonnet 4.6's default is `high` — set explicitly to `medium` for most apps to avoid unexpected latency.
- `max` can cause overthinking on trivial / structured tasks; stick to `high`/`xhigh` in most cases.
- Effort is behavioral, not a hard cap. For hard caps use `max_tokens` + `task_budget`.

## Task budgets

**Public beta, Opus 4.7 only.** Advisory total-token budget for an agentic loop, injected as a live countdown so Claude paces itself.

```python
response = client.beta.messages.create(
    model="claude-opus-4-7",
    max_tokens=128000,
    output_config={
        "effort": "high",
        "task_budget": {"type": "tokens", "total": 64000},  # min 20,000
    },
    betas=["task-budgets-2026-03-13"],
    messages=[{"role": "user", "content": "Review repo and propose refactor."}],
)
```

Gotchas:
- Advisory — Claude may exceed. Pair with `max_tokens` for a hard ceiling.
- Budget < 20,000 returns 400.
- Don't mutate `remaining` per request — kills prompt-cache prefix and the countdown drifts from real spend. Set once; let the server track.
- Opus 4.7 only. Not Sonnet 4.6, not Haiku 4.5, not on Claude Code.

## Structured outputs

Grammar-constrained decoding guarantees schema-valid JSON (`output_config.format`) or schema-valid tool inputs (`strict: true`). GA on Mythos, Opus 4.7/4.6/4.5, Sonnet 4.6/4.5, Haiku 4.5. No beta header.

```python
response = client.messages.create(
    model="claude-opus-4-7", max_tokens=1024,
    messages=[{"role": "user", "content": "Extract: Alice (alice@x.com), Enterprise demo Tue 2pm"}],
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
```

For tools, add `"strict": true` on the tool — name + input are guaranteed valid. See [tool-use.md#strict-tool-use](tool-use.md#strict-tool-use).

Gotchas:
- **Incompatible with Citations** (400).
- Set `additionalProperties: false` and list ALL fields in `required`, or the model invents optional ones.
- `pattern` keyword in JSON Schema is unsupported with `strict`.
- Schemas are cached up to 24h since last use (separate from prompt cache). HIPAA-eligible, but never put PHI in schema property names / enum / const / pattern.

## Citations

Inline, grounded citations on provided documents. Response interleaves `text` blocks with `citations` arrays. **Incompatible with structured outputs.**

```python
response = client.messages.create(
    model="claude-opus-4-7", max_tokens=1024,
    messages=[{"role": "user", "content": [
        {"type": "document",
         "source": {"type": "text", "media_type": "text/plain",
                    "data": "The grass is green. The sky is blue."},
         "title": "Facts", "citations": {"enabled": True}},
        {"type": "text", "text": "What color is grass?"},
    ]}],
)
for b in response.content:
    if b.type == "text":
        print(b.text)
        for cite in (getattr(b, "citations", None) or []):
            print(f"  ↳ {cite.cited_text!r} (doc {cite.document_index})")
```

Document types:
- **text/plain** → `char_location` citations, sentence-level chunks
- **PDF base64 / file_id** → `page_location` citations (1-indexed)
- **custom content** (array of text blocks) → `content_block_location` (0-indexed); no auto chunking — you control granularity by block size
- **search_result** → `search_result_location` (see next section)

Gotchas:
- All-or-nothing: every document in the request must have the same `citations.enabled` setting.
- `cited_text` doesn't count toward output tokens but chunking adds input overhead.
- Only text citations — scanned PDFs with no extractable text can't be cited.
- Cache documents via `cache_control: {"type": "ephemeral"}` on the document block for reuse across queries.

## search_result blocks

RAG-quality citations on your own corpora. Return as tool results or inline in user content.

```python
response = client.messages.create(
    model="claude-opus-4-7", max_tokens=1024,
    messages=[{"role": "user", "content": [
        {"type": "search_result",
         "source": "https://docs.example/auth", "title": "Auth Guide",
         "content": [{"type": "text", "text": "Use API key in Authorization header..."}],
         "citations": {"enabled": True}},
        {"type": "text", "text": "How do I authenticate?"},
    ]}],
)
# Response citations carry: type="search_result_location", source, title, cited_text,
# search_result_index, start_block_index, end_block_index
```

Required: `type`, `source`, `title`, `content` (array of text blocks). Citations default **off** — opt in. Finer granularity = more/smaller text blocks. `search_result_index` is 0-indexed across all search_result blocks in the whole request.

## Batch processing

Async bulk at **50% pricing**. Up to 100k requests or 256 MB per batch. Most complete < 1h; 24h hard expiry.

```python
from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
from anthropic.types.messages.batch_create_params import Request

batch = client.messages.batches.create(requests=[
    Request(custom_id="r1", params=MessageCreateParamsNonStreaming(
        model="claude-opus-4-7", max_tokens=1024,
        messages=[{"role": "user", "content": "Hello"}])),
    # ... up to 100k
])

# Poll
while client.messages.batches.retrieve(batch.id).processing_status != "ended":
    time.sleep(60)

for r in client.messages.batches.results(batch.id):
    if r.result.type == "succeeded":
        print(r.custom_id, r.result.message.content[0].text)
```

Gotchas:
- **Result order is not request order** — match by `custom_id` (regex `^[a-zA-Z0-9_-]{1,64}$`).
- `max_tokens: 0` rejected (must be ≥ 1).
- **Streaming not supported** inside batch requests.
- Results live 29 days from `created_at`; unprocessed expire at 24h (not billed).
- Fast mode and Priority Tier incompatible with batches.
- Cache hits are best-effort — use identical `cache_control` blocks across requests with 1h cache for shared prefixes.

## Fast mode

**Opus 4.6 only** — ~2.5× OTPS boost. Waitlisted beta. 6× price ($30/$150 per MTok).

```python
response = client.beta.messages.create(
    model="claude-opus-4-6", max_tokens=4096,
    speed="fast",
    betas=["fast-mode-2026-02-01"],
    messages=[{"role": "user", "content": "Refactor this..."}],
)
print(response.usage.speed)  # "fast" or fell-back "standard"
```

Gotchas:
- Other models + `speed="fast"` = error.
- Boost is OTPS (tokens/sec), not TTFT.
- Dedicated rate limit (429 on overflow) — set `max_retries=0` to fall back instantly.
- Switching fast↔standard **breaks prompt cache**.
- Not on Batch API or Priority Tier.

## Embeddings

Anthropic doesn't ship an embedding model. Use **Voyage AI** (separate API key).

```python
import voyageai, numpy as np
vo = voyageai.Client()  # VOYAGE_API_KEY

doc_embs = vo.embed(docs, model="voyage-4", input_type="document").embeddings
q_emb = vo.embed([query], model="voyage-4", input_type="query").embeddings[0]
best = int(np.argmax(np.dot(doc_embs, q_emb)))  # embeddings are L2-normalized
```

Models: `voyage-4-large` (quality), `voyage-4` (balanced), `voyage-4-lite` (cost), `voyage-code-3`, `voyage-finance-2`, `voyage-law-2`, `voyage-multimodal-3.5`. Default 1024 dims; Matryoshka-truncate to 256/512/2048 (re-normalize after slicing).

Gotchas:
- Always set `input_type="document"` vs `"query"` — asymmetric; wrong type loses ~5-10% recall.
- `output_dtype`: `float` (default), `int8`/`uint8` (4× smaller), `binary`/`ubinary` (32× smaller; length = dim/8).
- `voyage-law-2` / `voyage-finance-2` fixed at 1024 dims (no Matryoshka).

See [rag.md](rag.md) for the full retrieval pipeline using these embeddings.

## Multilingual

Claude handles most Unicode languages natively — no special parameter. Quality tiers (MMLU vs English):
- ES / PT / IT / FR: ~97-98%
- CJK / AR / HI: ~96-97%
- BN: ~95%
- SW: ~89% (Haiku 4.5 drops to 78%)
- YO: ~80% (Haiku 4.5: 53%)

Tips:
- State target input/output language explicitly rather than relying on auto-detect.
- Use native scripts, not transliteration.
- Low-resource languages: prefer Opus/Sonnet tier over Haiku.

## Sources

- `../docs/model-capabilities/extended-thinking.md`
- `../docs/model-capabilities/adaptive-thinking.md`
- `../docs/model-capabilities/effort.md`
- `../docs/model-capabilities/task-budgets.md`
- `../docs/model-capabilities/fast-mode.md`
- `../docs/model-capabilities/structured-outputs.md`
- `../docs/model-capabilities/citations.md`
- `../docs/model-capabilities/batch-processing.md`
- `../docs/model-capabilities/search-results.md`
- `../docs/model-capabilities/multilingual-support.md`
- `../docs/model-capabilities/embeddings.md`
