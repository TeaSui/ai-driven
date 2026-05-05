---
tags: [moc, messages-api]
---

# Messages API

The foundational `client.messages.create(...)` surface: request shape, response handling, and stop-reason branching.

- [[features-overview]] — what the Messages API supports at a glance.
- [[using-the-messages-api]] — request/response shape, roles, system prompts, temperature, max_tokens.
- [[handling-stop-reasons]] — branching on `end_turn`, `tool_use`, `max_tokens`, `pause_turn`, `refusal`, `model_context_window_exceeded`.

## Related
- [[../model-capabilities/index|Model capabilities]] — extended thinking, structured outputs, streaming.
- [[../tools/index|Tools]] — tool_use / tool_result blocks.
- [[../context-management/index|Context management]] — prompt caching + token counting.
- [[../index|Docs home]]
