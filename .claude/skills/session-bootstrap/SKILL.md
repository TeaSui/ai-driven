---
name: session-bootstrap
description: Use when starting any conversation - establishes how to find and use skills, requiring Skill tool invocation before ANY response including clarifying questions
---

<SUBAGENT-STOP>
If you were dispatched as a subagent to execute a specific task, skip this skill.
</SUBAGENT-STOP>

<EXTREMELY-IMPORTANT>
If there is even a 1% chance a skill might apply, you MUST invoke it. Not optional. Not negotiable.
</EXTREMELY-IMPORTANT>

## Instruction Priority

1. **User's explicit instructions** (CLAUDE.md, direct requests) — highest
2. **Skills loaded via the Skill tool** — override default system behavior where they conflict
3. **Default system prompt** — lowest

If CLAUDE.md says "don't use TDD" and a skill says "always use TDD," the user wins. "Add X" / "Fix Y" specify the *what*, not the *how* — they do not authorize skipping workflow skills.

## How to Invoke

Use the `Skill` tool. When a skill is invoked, its content is loaded and presented to you — follow it directly. **Never use Read on skill files** — that bypasses the activation mechanism.

# Using Skills

## The Rule

**Invoke relevant skills BEFORE any response or action — including clarifying questions.** Even a 1% chance counts. If an invoked skill turns out not to apply, you can discard it.

On each user message:
1. Scan for applicable skills (≥1% chance)
2. If yes → invoke via Skill tool → announce "Using [skill] to [purpose]"
3. If the skill has a checklist → create a TodoWrite item per step
4. Follow the skill exactly

Before `EnterPlanMode`: if you haven't already brainstormed, invoke the brainstorming skill first.

## Red Flags (rationalizations that mean STOP)

| Thought | Reality |
|---------|---------|
| "This is just a simple question / doesn't count as a task" | Questions and actions are both tasks. Check first. |
| "I need more context / let me explore first" | Skills tell you HOW to gather context. Check first. |
| "I remember this skill / I know what that means" | Skills evolve. Re-invoke — knowing the concept ≠ using it. |
| "The skill is overkill for this" | Simple things become complex. Use it. |
| "I'll just do this one quick thing first" | Check BEFORE doing anything. |
| "This feels productive" | Undisciplined action wastes time. Skills prevent that. |

## Skill Priority

When multiple skills apply:

1. **Process skills first** (brainstorming, debugging) — they determine HOW to approach
2. **Implementation skills second** (frontend-design, mcp-builder) — they guide execution

"Let's build X" → brainstorming, then implementation.
"Fix this bug" → debugging, then domain-specific.

## Skill Types

- **Rigid** (TDD, debugging): follow exactly; don't water down discipline.
- **Flexible** (patterns): adapt principles to context.

The skill itself tells you which.
