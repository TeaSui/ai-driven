---
name: eval-upgrade
description: Evaluate and upgrade Claude Code assets (agents, commands, rules, skills) — static quality checks with scoring, LLM upgrade proposals, and Arena mode (champion/challenger duels with points-based auto-promotion). Use when the user wants to audit, score, improve, or A/B-duel their .claude assets, or says /eval-upgrade.
---

# /eval-upgrade — Evaluate & upgrade Claude Code assets

Tool location: `/Users/tungnguyen/TYME/Tools/Claude/eval-upgrade` (canonical docs: its `README.md` — read it for checks, scoring formula, arena rules, API, and security model; do not restate it here).

When this command is invoked, run the matching mode below via Bash from the tool directory and report the output. Hooks are out of scope by design (executable code).

## Modes

```bash
cd /Users/tungnguyen/TYME/Tools/Claude/eval-upgrade

# Evaluate (default mode when the user gives no subcommand)
node bin/run.mjs [--target <dir>] [--types agents,commands,rules,skills] [--llm] [--json]

# Dashboard — when requested, open the local URL without copying its session token into chat/logs
node server.mjs

# Arena — champion/challenger duels
node bin/arena.mjs challenger <artifact-id>   # register challenger + task bank (needs a prior eval run)
node bin/arena.mjs duel <artifact-id> [N]     # run N duel rounds; auto-promotes at threshold
node bin/arena.mjs status                     # list arenas
node bin/arena.mjs promote <artifact-id>      # manual promotion (backup kept)
node bin/arena.mjs discard <artifact-id>      # retire challenger

# Config — inspect documented non-sensitive toggles through an authorized sanitized view
# Never dump the entire configuration or credential values into the transcript.
```

Artifact ids look like `skills/goal`, `agents/code-reviewer`, `rules/patterns`, `commands/prove-it`.

## Guardrails when running for the user

- Duels and LLM review spend real tokens (a duel ≈ 3 `claude -p` calls). Confirm before
  launching a long duel series (`duel <id> 10`) or `--llm` over many artifacts.
- Never edit files under `arena/`, `results/`, or `proposals/` by hand — they are generated.
- Promotion/apply write into the target `.claude` with a timestamped `.bak`; if the user
  wants a promotion undone, restore from the backup path printed in the output.
- A shell wrapper exists too: `eval-upgrade [eval|dashboard|arena|config] …` (in `~/.local/bin`).

## Real-session auto-trigger (opt-in, background only)

The tool supports an optional async `PostToolUse` hook at `hooks/auto-trigger.mjs`
(matcher `Skill|Task|Agent`). Do not assume it is registered: verify active hooks through
`/hooks` or an authorized sanitized settings view before claiming auto-trigger is available.
Do not modify hook registration as part of an evaluation. If registered, a real Skill/Agent call
checks `config.json`'s `autoTrigger.enabled` (default `false`) and the artifact's type
toggle — if both are on AND an arena already exists for that artifact, it fires one
background duel round (throttled, default once/day/artifact). It never auto-registers a
challenger and never blocks or prints into the transcript — see README.md "Real-session
auto-trigger" for the full guard chain. This is NOT a live in-session A/B — no interception
point can fork a real tool call; this only advances an already-started offline arena.
