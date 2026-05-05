---
tags: [moc, tools]
---

# Tools

Client-side custom tools and Anthropic-hosted tools. Scaling-level concerns (search, streaming, context) live in [[../tool-infrastructure/index|tool-infrastructure]].

## Fundamentals
- [[tool-use-overview]] — when and why to use tools.
- [[how-tool-use-works]] — `tool_use` / `tool_result` block flow.
- [[tutorial-build-a-tool-using-agent]] — end-to-end walkthrough.

## Authoring client-side tools
- [[define-tools]] — JSON Schema, `name`, `description`, `input_schema`.
- [[handle-tool-calls]] — pairing `tool_use.id` with `tool_result`.
- [[parallel-tool-use]] — multiple tool_use blocks per turn.
- [[strict-tool-use]] — `strict: true` for guaranteed schema compliance.
- [[tool-runner-sdk]] — SDK's Tool Runner.
- [[tool-use-with-prompt-caching]] — cache interactions with the `tools` array.
- [[troubleshooting-tool-use]] — common 400s and recovery patterns.

## Anthropic-hosted: server tools
Claude executes these in Anthropic's infrastructure — no client-side handler needed.
- [[server-tools]] — overview of the server-tool category.
- [[web-search-tool]] — `web_search`.
- [[web-fetch-tool]] — `web_fetch`.
- [[code-execution-tool]] — `code_execution` sandbox (no internet; files via [[../files/files-api|Files API]]).
- [[advisor-tool]] — `advisor`.

## Anthropic-hosted: client tools (execute locally, Anthropic-defined schema)
These use Anthropic's schema but run in *your* environment.
- [[memory-tool]] — `memory`.
- [[bash-tool]] — `bash`.
- [[computer-use-tool]] — `computer_use`.
- [[text-editor-tool]] — `text_editor`.

## Related
- [[../tool-infrastructure/index|Tool infrastructure]] — tool search, programmatic calling, streaming.
- [[../mcp/index|MCP]] — remote MCP servers as a tool source.
- [[../index|Docs home]]
