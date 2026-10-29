# Current Claude API guide

Verified 2026-10-23. Primary sources: [models](https://platform.claude.com/docs/en/models/overview), [thinking](https://platform.claude.com/docs/en/build-with-claude/thinking), [effort](https://platform.claude.com/docs/en/build-with-claude/effort), [Opus migration](https://platform.claude.com/docs/en/models/opus-5-5/migration-guide), [Haiku migration](https://platform.claude.com/docs/en/models/haiku-5-5/migration-guide), [Python SDK](https://github.com/anthropics/anthropic-sdk-python).

| Model ID | API starting effort | Intended use |
|---|---|---|
| `claude-opus-5-5` | `medium` | Default substantial coding/knowledge integration |
| `claude-sonnet-5-5` | Set explicitly; API default is `high` | Clear bounded tasks where measured quality/cost fits |
| `claude-haiku-5-5` | `medium`; evaluate `low` for mechanical work | Extraction, routing and volume/latency sensitive tasks |
| `claude-fable-5-1` | `high` | Hard reasoning and long-horizon autonomy |

All four have native 1M context and up to 128K output. These are capacity limits, not suggested request sizes. Use the Models API for account/platform capabilities. Do not fabricate date-suffixed IDs or assume a provider serves a model merely because it exists. Haiku has tiered API pricing above 100K input tokens: long context is not always the cheapest route. Confirm current prices before estimating cost.

## Requests and thinking

- Use an official SDK supported by the project, loading credentials from its existing secure mechanism. Never log keys or copy account credentials into examples.
- Current requests default to adaptive reasoning; an explicit `thinking={"type": "adaptive"}` is supported. Set depth with `output_config={"effort": "medium"}` or the target's appropriate effort. Fixed `enabled`/`budget_tokens` forms are rejected on these models.
- Fable 5.1 and Opus 5.5 cannot disable thinking. Sonnet 5.5 rejects `disabled`; its separate `between_tools` option is API-specific. Haiku 5.5 allows disabled only within documented effort constraints. Code does not expose those API exceptions as a thinking-off setting for these models.
- Do not carry over temperature/top_p/top_k, assistant prefill or forced tool_choice from a legacy example. Check the target's migration guide; current-model support differs and unsupported forms return errors.
- `max_tokens` includes thinking and the final response. Keep a finite adequate output budget and stream long requests. In Python, the SDK's stream context manager and `get_final_message()` collect a complete typed response; in TypeScript use its documented `finalMessage()` helper.

## Read and continue responses safely

Check `stop_reason`: `end_turn` is complete; `max_tokens` is partial; `tool_use` needs validated tools and results; `pause_turn` needs the documented continuation; `refusal` needs explicit handling; context-limit and other documented stops need their own paths. A 200 response is not proof that the requested task completed.

Inspect each block's type before accessing text. Carry the full assistant content forward. Put matching tool_result blocks first in the next user message, then new user text. Validate fully received tool input, especially with eager/fine-grained streaming. Never run incomplete or refused tool output.

For supported first-party requests, consider the documented server-side default refusal fallback: beta `server-side-fallback-2026-07-01` with `fallbacks: "default"`. Tell the application owner it may change the served model and billing; respect a fixed-model requirement. Haiku 5.5 has no server-side refusal fallback; provider/Batches support differs. Follow the linked SDK/docs for the exact current method rather than pasting old array/header combinations. This setup refresh does not enable paid requests or change account fallbacks itself.

## State, cost and quality

These models preserve thinking tied to the producing model/conversation. Keep stable system/tool definitions and prior turns unchanged during a normal loop; append new information. Use documented compaction/edit controls instead of aggressive ad-hoc deletion. At a model switch, follow replay rules and measure cache/cost implications.

Use `output_config.format`/schema-constrained output and strict tools where supported, then validate business invariants. Log usage, cache reads/writes, latency, completion status and served model with sensitive content redacted. Verify SDK versions and migration changes instead of pinning an imagined dependency release. Test representative tasks before claiming quality or savings; a syntax check proves only syntax.
