---
tags: [moc, model-capabilities]
---

# Model capabilities

Per-feature details for the model-level knobs exposed by `messages.create`.

## Thinking
- [[extended-thinking]] — what extended thinking is, when it helps, block shape.
- [[adaptive-thinking]] — `thinking={"type":"adaptive"}` (required on Opus 4.7).
- [[effort]] — `output_config.effort` levels (low → xhigh).
- [[task-budgets]] — per-task thinking budgets.

## Output shape
- [[structured-outputs]] — `output_config.format` with `json_schema` (replaces prefill on 4.6/4.7).
- [[citations]] — citation blocks and `search_result` sources.
- [[streaming-messages]] — SSE event types and deltas.
- [[streaming-refusals]] — how refusals appear in the stream.

## Performance & scale
- [[fast-mode]] — Opus 4.6 only; `speed="fast"` at 6× price.
- [[batch-processing]] — 50% batch API.
- [[multilingual-support]] — supported languages and caveats.

## Retrieval primitives
- [[search-results]] — `search_result` block type.
- [[embeddings]] — VoyageAI embedding models (primary partner for Claude).

## Related
- [[../context-management/prompt-caching|Prompt caching]] — pairs with most features above.
- [[../tool-infrastructure/fine-grained-tool-streaming|Fine-grained tool streaming]] — streaming extension for tool input JSON.
- [[../index|Docs home]]
