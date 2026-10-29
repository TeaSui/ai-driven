# Claude models and effort

Verified 2026-10-23 against [Code model configuration](https://code.claude.com/docs/en/model-config), [subagents](https://code.claude.com/docs/en/sub-agents), and [Platform model overview](https://platform.claude.com/docs/en/models/overview). This is a starting policy, not a measured optimum for every task. Recheck these sources when upgrading.

| Model | Exact Claude API ID | Starting Code effort | Choose for |
|---|---|---|---|
| Opus 5.5 | `claude-opus-5-5` | `medium` | Main coding session, ambiguous implementation, integration and knowledge work |
| Sonnet 5.5 | `claude-sonnet-5-5` | `medium` | Bounded implementation with a clear contract and acceptance checks |
| Haiku 5.5 | `claude-haiku-5-5` | `low` | Simple extraction, enumeration, routing and mechanical transformations |
| Fable 5.1 | `claude-fable-5-1` | `high` | Hard unresolved reasoning, architecture/investigation and long autonomous work |

Start with Opus for an unspecified substantial task. Use a smaller model when task scope and evaluations support it. Raise effort when investigation is shallow; try a stronger model when sufficient investigation still gives poor results. Keep difficult security reviews and ambiguous engineering on a capable model. Do not treat a cheap model as a substitute for validation. Choose Fable when its extra capability matters, rather than making every specialist use it.

`fable`, `opus`, `sonnet`, `haiku` currently resolve to these versions on the Anthropic API. Other providers may lag; the Claude apps gateway can resolve `fable` to Fable 5. Select an exact ID when version certainty matters. Do not overwrite working provider mappings or claim account access from an alias alone. Code minimums: Fable 5.1 v2.1.257, Opus 5.5 v2.1.280, Sonnet 5.5 v2.1.284, Haiku 5.5 v2.1.293.

## Use in Claude Code

- `claude --model opus --effort medium`: ordinary engineering session.
- `claude --model sonnet --effort medium`: clearly scoped implementation.
- `claude --model haiku --effort low`: mechanical work you can verify.
- `claude --model claude-fable-5-1 --effort high`: difficult/long work on a provider serving that ID.
- `/model` and `/effort` inspect/change the session. `/status` checks loaded configuration; `/context` shows context usage; `/hooks` shows active hooks.
- `opusplan` uses Opus in plan mode and Sonnet for execution. Use it deliberately when that tradeoff fits the task.

Claude Code supports `low`, `medium`, `high`, `xhigh`, `max` for these models. Save per-model preferences under `modelSettings` using canonical keys and `effortLevel` (`low` through `xhigh`). `max` is a session choice, not a saved modelSettings level. User-level legacy top-level `effortLevel` does not apply to Opus 5.5 and newer releases. Code manages adaptive reasoning; API `thinking` objects and fixed thinking-token budgets are not Code settings. The four current models cannot have thinking turned off in Code.

All four have native 1M context; an added `[1m]` suffix is unnecessary. Larger context is capacity, not a reason to fill it with unrelated rules, logs or repeated documentation. Keep stable instructions stable for cache reuse and use `/context` to inspect actual loading. Fable can require usage credits depending on the plan; the picker shows eligibility and billing prompts. Do not enable paid fast/extra-usage modes automatically.

## Custom agents

Choose model at dispatch only when it differs from the agent's normal job. Runtime/provider restrictions and explicit host overrides apply; absent those, a dispatch override precedes agent frontmatter, and absent frontmatter the agent inherits the dispatching session's model. Omitting a model never guarantees Sonnet.

Agent frontmatter supports `model` (alias, exact ID, `inherit`) and `effort`. Use YAML lists for preloaded `skills`. Preloads load full bodies; keep only necessary specialist context. Give every model a clear goal, scope and evidence requirements. Use a complete template for mechanical Haiku work; use concise constraints and outcome criteria for Fable. Ask for a decision and concise rationale, not private chain-of-thought. Preserve domain/security rules regardless of the model.

## API is separate

API code uses request-level `output_config.effort`, adaptive reasoning and adequate `max_tokens`. Do not transplant Code settings into requests or API controls into settings.json. Sonnet's API default effort is `high`, while Code defaults to `medium`. Use the current guide in `~/.claude/skills/building-with-the-claude-api/` for API work.
