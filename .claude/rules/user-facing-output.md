# User-Facing Output

## Scope

Applies to final responses and user-visible progress updates from the main session. It does **not** replace `rules/subagent-return-format.md`, which remains the standard for subagent-to-dispatcher returns.

## Principle

Optimize user-facing output for user decision-making, not internal completeness. Return the smallest useful answer that lets the user understand the outcome, important risk, verification status, and next step.

## Default final response shape

For completed work, use this order:

1. **Outcome** — what was done or concluded.
2. **Key changes/findings** — only the important items.
3. **Verification** — what was checked, or what could not be checked.
4. **Next step** — only when useful and specific.

For tiny tasks, collapse this to one or two sentences.

## Length budget

- Simple answer or small edit: 1-3 sentences.
- Normal implementation, review, or doc update: 3-7 bullets or short paragraphs.
- Complex workflow: concise summary plus evidence paths; do not paste full artifacts or logs.

Prefer paths and compact evidence over pasted content. If details are persisted in files or logs, cite the path instead of copying the content into the response.

## Required content

Always include these when applicable:

- Whether the task is done, partially done, or blocked.
- Files changed, when code or docs were changed.
- Tests/checks run, when claiming verification.
- Explicit caveat when verification was not run or was degraded.
- Material risk, blocker, or user action needed before proceeding.

## Avoid unless requested

Do not include:

- Step-by-step internal reasoning.
- Every command run.
- Full diffs.
- Full logs.
- Full subagent returns.
- Long file lists when a directory or key files are enough.
- Repeated context already known to the user.
- Generic "let me know if..." endings.

## Review responses

For review-style requests, lead with findings ordered by severity. If there are no findings, say so directly and mention residual test/verification gaps only if they matter.

## Blocked responses

When blocked, include only:

1. The blocker.
2. What was tried, briefly.
3. The next input, permission, or external change needed.

Do not bury the blocker under background context.

## Subagent aggregation

When reporting subagent results to the user, summarize the status and cite evidence paths. Do not paste the full subagent return unless the user asks for it. Preserve any `DONE_WITH_CONCERNS`, `NEEDS_CONTEXT`, or `BLOCKED` status plainly.

## Compact templates

Completed work:

```md
Done. Updated <main thing> in <path(s)>.

Verified with <checks>. <Caveat if relevant.>
```

Blocked:

```md
Blocked: <reason>.

Tried <brief attempt>. Need <specific next input/action>.
```

Review with no findings:

```md
No findings. I checked <scope>.

Verification gap: <only if relevant>.
```
