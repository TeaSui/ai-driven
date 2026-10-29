# Subagent returns

Return a status (`DONE`, `DONE_WITH_CONCERNS`, `NEEDS_CONTEXT`, `BLOCKED`), real evidence paths, and one met/not-met check per exit criterion. Include material concerns or missing inputs. A tool failure is not a successful empty result.
The dispatcher reads cited evidence and unresolved concerns before accepting completion. Diagnose failures and amend context/scope before retrying. Never invent an agent result when dispatch is unavailable.
Full examples and handling: `~/.claude/references/workflows/subagent-return-format.md`.
