---
tags: [moc, context]
---

# Context management

Managing the context window, cache, and token budget across turns.

- [[context-windows]] — 200k / 1M window tiers.
- [[compaction]] — server-side compaction of long histories.
- [[context-editing]] — `clear_tool_uses` / `clear_thinking` strategies.
- [[prompt-caching]] — `cache_control`, 5m vs 1h TTL, breakpoints, invalidation order (`tools → system → messages`).
- [[token-counting]] — `messages.count_tokens`.

## Related
- [[../tools/tool-use-with-prompt-caching|Tool use with prompt caching]]
- [[../model-capabilities/batch-processing|Batch processing]] — 50% price tier.
- [[../index|Docs home]]
