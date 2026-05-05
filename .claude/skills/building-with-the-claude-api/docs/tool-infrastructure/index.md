---
tags: [moc, tool-infrastructure]
---

# Tool infrastructure

Scaling-level concerns when you have many tools, streaming tool use, or need deterministic tool-call logic.

- [[tool-reference]] — canonical reference for tool-related request fields.
- [[manage-tool-context]] — keeping the `tools` array + `tool_result` history under control.
- [[tool-combinations]] — what can / cannot coexist (e.g. computer use + others).
- [[tool-search-tool]] — regex/BM25 tool search for large tool inventories.
- [[programmatic-tool-calling]] — force specific tool calls.
- [[fine-grained-tool-streaming]] — stream partial `input_json_delta`; disables JSON validation, so handle malformed deltas.

## Related
- [[../tools/index|Tools]] — authoring + catalog.
- [[../context-management/prompt-caching|Prompt caching]] — `tools` array cache interactions.
- [[../index|Docs home]]
