---
name: building-with-the-claude-api
description: "Implement or migrate Claude API integrations using the official SDK: current model selection, adaptive reasoning, streaming, tool loops, structured outputs, caching and evaluations. Use for Anthropic API code; Claude Code CLI settings/hooks are a separate surface."
---

# Build with the current Claude API

Use the official SDK for the project's language; read the installed version and current official documentation before choosing methods. Do not replace another provider's code without an explicit migration request. Preserve existing contracts, user authorization and security rules.

Start with [references/current-models.md](references/current-models.md), verified 2026-10-23, for Fable 5.1 and Opus/Sonnet/Haiku 5.5. For Code model/effort selection use `~/.claude/references/model-guide.md`; API parameters do not belong in settings.json.

## Current reference routes

- Models, IDs, capability discovery: https://platform.claude.com/docs/en/models/overview
- Choosing/migrating: the target model's migration page linked from that overview. Read breaking changes as well as changing the ID.
- Python SDK and migration: https://github.com/anthropics/anthropic-sdk-python
- TypeScript SDK: https://github.com/anthropics/anthropic-sdk-typescript
- Thinking/effort: https://platform.claude.com/docs/en/build-with-claude/thinking and https://platform.claude.com/docs/en/build-with-claude/effort
- Streaming: https://platform.claude.com/docs/en/build-with-claude/streaming
- Tool use: https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview
- Structured outputs: https://platform.claude.com/docs/en/build-with-claude/structured-outputs
- Caching: https://platform.claude.com/docs/en/build-with-claude/prompt-caching
- Refusals/fallback: https://platform.claude.com/docs/en/build-with-claude/refusals-and-fallback

Read only the pages relevant to the task. Prefer current official docs and the matching SDK source over a remembered API or an old local snippet. If a bundled current `claude-api` skill is actually exposed by this runtime, it can provide additional current language-specific references; do not assume it is installed.

## Integration checks

Choose the model/effort using representative quality, latency and cost evidence. Default an unspecified substantial workload to Opus 5.5; use smaller models for demonstrated bounded tasks. Use adaptive reasoning, adequate max_tokens and streaming for potentially long responses. Control depth with supported parameters rather than private chain-of-thought prompts.

Inspect stop_reason before treating content as successful. Iterate typed content blocks; the first block can be thinking, not text. Preserve tool-use IDs, assistant content and the required next-user tool_result ordering. Validate tool schemas, arguments and outputs before side effects. Keep sensitive data out of diagnostics.

Keep the conversation append-only for preserved-thinking models. Rewriting system/tools/earlier turns mid-session requires the documented controls; do not copy historical history-pruning recipes into current clients. Measure usage and cache statistics rather than assuming token savings.

## Historical curriculum

The remaining `references/*.md` (except current-models.md) and `docs/` are an older preserved curriculum. They contain obsolete model defaults, thinking budgets, sampling/prefill recipes, API namespaces and capabilities. They are not current executable examples. Consult them only for an explicitly older model or background concepts, then recheck every API shape with live docs. Do not automatically load them, rename model strings in their code, or treat their old feature matrices as current. Retaining them preserves domain knowledge without making them the default implementation path.
