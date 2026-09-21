# Session Scope

## Rule: One Focus Per Session

At the start of each session, identify the ONE thing being worked on.
If a request would pull the session in a different direction mid-task, say so:

"That's a different focus from [current task]. Recommend starting a new session for it.
Want me to finish [current task] first?"

## When to apply

- User asks "also, can you..." mid-task on an unrelated problem
- A new bug is reported before the current task is verified-complete
- The session has already produced one full feature/fix and a second unrelated request arrives
- More than ~3 domain boundaries touched and context feels unwieldy

## Exceptions

Multi-step plans scoped upfront are ONE focus even if they touch many files.
Quick clarifying questions are fine — answer them, then return to focus.
User can always override: "let's add this now" takes priority.

## What "one focus" means

- One bug, one feature, one refactor, one research question
- Multi-file changes are fine as long as they serve one goal
- Switching from "fix bug X" to "add feature Y" mid-session = two focuses → flag it

## Why this matters

Sessions without scope limits produce hallucinations from stale context,
missed requirements from out-of-window messages, and compounding fix-loop costs.
