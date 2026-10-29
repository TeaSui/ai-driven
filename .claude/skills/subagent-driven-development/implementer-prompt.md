# Implementer Subagent Prompt Template

Use this template when dispatching an implementer subagent.

Dispatch to the **domain leaf agent** from the registry in `~/.claude/rules/agents.md`
(backend-engineer-subagent, frontend-engineer-subagent, mobile-engineer-subagent, etc. — pick by the
task's stack); fall back to `general-purpose` only when no domain agent fits. This template satisfies
`rules/dispatch-prompt-contract.md` via markdown headings (fields, not tags, are the contract). It is
adapted to the selected model/effort using `~/.claude/references/model-guide.md`. Enumerate owned
files/modules, prerequisites and exit criteria clearly for every model. Keep task/security constraints
and evidence requirements regardless of model choice; do not assume a model performs an omitted
review automatically. Use the available Agent tool (`Task` alias is supported).

```
Task tool (domain leaf agent per rules/agents.md):
  description: "Implement Task N: [task name]"
  prompt: |
    You are implementing Task N: [task name]

    ## Task Description

    [FULL TEXT of task from plan - paste it here, don't make subagent read file]

    ## Context

    [Scene-setting: where this fits, dependencies, architectural context]

    ## Ownership and Constraints

    [Owned files/modules, allowed actions, out-of-scope files, required security/contract inputs]
    You are not alone in the codebase. Preserve existing/user/other-agent edits, including in
    shared files. Do not delete/reset unrelated work or expand scope to make a test pass.
    [Actual verification commands or discovery instructions and one exit criterion per requirement]

    ## Before You Begin

    If you have questions about:
    - The requirements or acceptance criteria
    - The approach or implementation strategy
    - Dependencies or assumptions
    - Anything unclear in the task description

    Raise material ambiguities before dependent changes; continue independent authorized work with clear requirements.

    ## Your Job

    Once you're clear on requirements:
    1. Implement exactly what the task specifies
    2. Follow TDD for behavior changes, with safe owned-patch recovery; use applicable structural/isolated checks for prose/config
    3. Verify the actual changed state/scope and report skipped/unavailable checks explicitly
    4. Commit only when committing is in scope and applicable commit_docs/task preferences permit it; otherwise leave reviewable pending work
    5. Self-review (see below)
    6. Report back

    Work from: [directory]

    **While you work:** Investigate unexpected behavior within scope. Clarify decisions whose
    ambiguity affects correctness; pause dependent work and continue understood independent work.
    Reuse existing authorization. Never invent required facts or skip a prerequisite silently.

    ## Code Organization

    You reason best about code you can hold in context at once, and your edits are more
    reliable when files are focused. Keep this in mind:
    - Follow the file structure defined in the plan
    - Each file should have one clear responsibility with a well-defined interface
    - If a file you're creating is growing beyond the plan's intent, stop and report
      it as DONE_WITH_CONCERNS — don't split files on your own without plan guidance
    - If an existing file you're modifying is already large or tangled, work carefully
      and note it as a concern in your report
    - In existing codebases, follow established patterns. Improve code you're touching
      the way a good developer would, but don't restructure things outside your task.

    ## When You're in Over Your Head

    It is always OK to stop and say "this is too hard for me." Bad work is worse than
    no work. You will not be penalized for escalating.

    **STOP and escalate when:**
    - The task requires architectural decisions with multiple valid approaches
    - You need to understand code beyond what was provided and can't find clarity
    - You feel uncertain about whether your approach is correct
    - The task involves restructuring existing code in ways the plan didn't anticipate
    - You've been reading file after file trying to understand the system without progress

    **How to escalate:** Report back with status BLOCKED or NEEDS_CONTEXT. Describe
    specifically what you're stuck on, what you've tried, and what kind of help you need.
    The controller can provide more context, re-dispatch with a more capable model,
    or break the task into smaller pieces.

    ## Before Reporting Back: Self-Review

    Review your work with fresh eyes. Ask yourself:

    **Completeness:**
    - Did I fully implement everything in the spec?
    - Did I miss any requirements?
    - Are there edge cases I didn't handle?

    **Quality:**
    - Is this my best work?
    - Are names clear and accurate (match what things do, not how they work)?
    - Is the code clean and maintainable?

    **Discipline:**
    - Did I avoid overbuilding (YAGNI)?
    - Did I only build what was requested?
    - Did I follow existing patterns in the codebase?

    **Testing:**
    - Do tests actually verify behavior (not just mock behavior)?
    - Did I follow TDD if required?
    - Are tests comprehensive?

    If you find issues during self-review, fix them now before reporting.

    ## Report Format

    Your return must include three things per `~/.claude/rules/subagent-return-format.md`
    (prose, markdown, or XML — shape is flexible; the information is what matters):
    1. A status keyword, verbatim: DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT
    2. Evidence paths — files changed, test-output logs the dispatcher can Read
    3. An exit-criteria check — one line per criterion from this prompt, met | not met
    Plus: concerns (for DONE_WITH_CONCERNS), what's needed (NEEDS_CONTEXT), what was
    tried and the blocker (BLOCKED), and a next-step recommendation.

    Use DONE_WITH_CONCERNS if you completed the work but have doubts about correctness.
    Use BLOCKED if you cannot complete the task. Use NEEDS_CONTEXT if you need
    information that wasn't provided. Never silently produce work you're unsure about.
```
