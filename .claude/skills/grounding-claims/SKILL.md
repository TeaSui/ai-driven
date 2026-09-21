---
name: grounding-claims
description: Use when about to state a fact about the codebase, a library API, a CLI flag, a command's behavior, a commit, or any verifiable external detail - requires citing a source (file:line, tool output, context7 doc) or saying "I don't know"; evidence before assertion, every time
---

# Grounding Claims

## Overview

Fabrication is a correctness bug. Inventing a function name, file path, flag, API shape, or commit SHA is a defect — treat it like a broken test, not a wording choice.

**Core principle:** If you cannot cite the source, you cannot make the claim. "I don't know" is a first-class answer.

**Companion rule:** `rules/reduce-hallucinations.md` — same principle, applied to all agents.

## When to Use

**Before stating any of these:**

- A file exists, or a file contains X
- A function does Y / has signature Z
- A CLI flag, env var, or config key exists
- A library has API X (especially if not verified via `context7`)
- A commit, PR, or branch did Z
- A test / lint / build passes
- A subagent's evidence path proves something
- "Recent" changes, "current" state, "latest" version

**Also when:**

- Answering "does the codebase do X?" without having read the relevant files
- Summarizing a file from memory of a prior session
- Describing external system behavior without documentation in hand

**When NOT to use:** writing throwaway scratch code, brainstorming where speculation is explicitly the mode, or translating the user's own stated facts back to them.

## The Gate Function

```
BEFORE asserting any verifiable fact:

1. IDENTIFY: Is this a verifiable claim? (path, name, flag, behavior, version, history)
2. LOCATE: Where is the source of truth?
   - Codebase fact → Read / Grep / Glob
   - Library fact → context7
   - CLI fact → --help / man / code
   - History fact → git log / git show / gh
   - Runtime fact → run the command
3. FETCH: Use the tool. Don't recall.
4. CITE: Quote with `path:line` or include tool output
5. IF fetch is not possible now → say "I don't know" or "let me check"

Skip any step = fabrication, not shorthand.
```

## Quick Reference

| Claim | Verify with | Cite as |
|-------|-------------|---------|
| File exists / contains X | `Read` or `Glob` | `path/to/file.ts:42` |
| Function behavior | `Read` the function | `path:start-end` + quote |
| Callers of X | `Grep` | `path:line` per hit |
| Library API | `context7` | doc URL or library+version |
| CLI flag | `--help` / code | command output |
| Env var | Code grep + docs | `path:line` |
| Commit / PR | `git log` / `gh` | SHA + short title |
| Test passes | Run the command | log path + exit code |
| "Recent" changes | `git log --since=` | commit list |

## Common Hallucination Patterns (and the fix)

| Pattern | Fix |
|---------|-----|
| "There's probably a `configure()` method…" | `context7` the library OR `Grep` the codebase |
| "The file should be at `src/config.ts`" | `Glob 'src/**/config.*'` first |
| "This typically uses env var `API_URL`" | `Grep 'API_URL'` + read the config loader |
| "The flag is `--dry-run`" | Run `cmd --help` and read the actual flags |
| "Recent commits added…" | `git log --oneline -20` |
| "Subagent said evidence is in `/tmp/x.log`" | `Read /tmp/x.log` — if missing, treat as BLOCKED |
| "Based on my memory, this project uses…" | Verify file exists now (see `auto memory` → "Before recommending from memory") |

## Red Flags — STOP

- You're about to write a function name, flag, or path you have NOT seen in this session's tool output.
- You're answering "does library X do Y?" without a `context7` call in this session.
- You're summarizing a file you have not `Read` in this session.
- You're accepting a subagent's evidence path without `Read`ing it.
- You catch yourself saying "typically", "usually", "should be", "probably" about a verifiable fact.
- You're about to cite a commit SHA, date, or author from recall.

**Any red flag → verify or say "I don't know."**

## Rationalization Table

| Excuse | Reality |
|--------|---------|
| "I'm confident it's named that" | Confidence ≠ evidence. Grep. |
| "Libraries usually have this API" | Usually ≠ this library, this version. Call context7. |
| "The subagent is trustworthy" | Then `Read` its cited path — takes 2 seconds. |
| "It's a minor detail" | Minor-looking details become load-bearing in the user's next step. |
| "I can't tool-call for everything" | You can for anything you're about to assert. |
| "Saying 'I don't know' looks bad" | Fabricating looks worse, ends trust faster. |
| "It's just a summary, not a claim" | Summaries ARE claims. They get acted on. |

## Patterns

**Codebase claim:**
```
❌ "The auth handler validates JWT tokens."
✅ [Read pkg/auth/handler.go:40-70] "pkg/auth/handler.go:52 calls jwt.Parse with the signing key from config.JWTSecret."
```

**Library claim:**
```
❌ "Redis supports SETEX for expiring keys."
✅ [context7 redis 7.x] "Per redis 7.x docs, SET with EX option is preferred; SETEX is legacy."
```

**History claim:**
```
❌ "This was changed recently."
✅ [git log --oneline -10 pkg/auth/] "Last 3 changes: abc123 (2026-05-09) fix JWT exp, def456 (2026-05-07)..."
```

**Unknown:**
```
❌ "I think it's in src/config/"  [guessing]
✅ "I haven't searched for it — let me check." [then Glob]
✅ "I don't see it in the files I've read. Want me to search?"
```

**Subagent evidence:**
```
❌ Subagent: "Status: DONE, evidence in /tmp/auth-test.log"
   You: "Great, proceeding." [never read the log]
✅ Subagent: "Status: DONE, evidence in /tmp/auth-test.log"
   You: [Read /tmp/auth-test.log → verify exit 0, coverage ≥80%] "Confirmed, proceeding."
```

## Interaction with Other Skills

- `verification-before-completion` — success claims must have fresh command output. This skill covers *all* factual claims, not just success claims.
- `systematic-debugging` — forbids fix-by-guess; this skill forbids claim-by-guess.
- `receiving-code-review` — "verify before implementing suggestions" overlaps: if a reviewer cites an API that doesn't exist in the current library version, `context7` it before changing code.
- `rules/subagent-return-format.md` — dispatchers verify every cited evidence path by `Read`; fabricated = BLOCKED.

## The Bottom Line

**No claim without a citation. No citation without a fresh verification.**

If the cost of verifying is higher than the value of the claim, don't make the claim — say "I don't know" and move on.
