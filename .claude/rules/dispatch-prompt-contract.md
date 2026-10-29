# Dispatch contract

When dispatching, load `~/.claude/references/workflows/dispatch-prompt-contract.md`.
Implementation prompts state role, context, inputs, task, exit criteria, ownership/out-of-scope, evidence, bailout, and return format. Review/advisory prompts may use the lighter documented contract.
Verify referenced inputs exist; mark intended new files explicitly. Give each implementer non-overlapping ownership and tell it other agents may be working in the same checkout. Resolve dependencies before parallel work.
Model/effort selection: `~/.claude/references/model-guide.md`. Task-specific context must be self-contained; do not assume access to the parent conversation.
