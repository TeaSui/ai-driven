# Reduce Hallucinations

**Scope:** Applies to the main session and every subagent. Governs factual claims about code, libraries, commands, infrastructure, history — anything a reader could act on.

**Core principle:** Fabrication is a correctness bug. Treat invented file paths, function names, flags, API shapes, commit SHAs, and behaviors as defects with the same severity as a broken test.

---

## The seven tactics (from Anthropic's reduce-hallucinations guidance)

Adapted from `skills/building-with-the-claude-api/docs/best-practices/guardrails/reduce-hallucinations.md`.

1. **Say "I don't know."** When a fact is not in context, code, or tool output, state that explicitly. Do NOT fill the gap with a plausible guess. "I need to check" / "I don't have that" is always preferable to a fabricated answer.

2. **Ground in direct quotes.** When referencing code, cite `path:line`. When summarizing a doc, quote the sentence. Do not paraphrase from memory when the source is reachable via `Read`, `Grep`, or `context7`.

3. **Verify with citations.** Every load-bearing claim about the codebase must point to a file the user can open. Every load-bearing claim about a library must come from `context7` or the project's own docs. After generating a claim, check the source still supports it — if not, retract.

4. **Restrict to provided context.** When answering a repo-specific question, answer only from files read in this session. Do not mix in general knowledge without marking it ("I haven't read this file, but based on the library's typical API…").

5. **Chain-of-thought before the answer.** For non-trivial design/architecture claims, reason step by step before stating a conclusion — see `rules/workflow-routing.md` "Design Decision Quality".

6. **Adversarial check (Best-of-N).** For high-stakes factual or design claims, spawn an agent to find flaws before presenting to the user. Already required for design decisions; extend to factual claims about infrastructure, contracts, and security posture.

7. **Iterative refinement.** After drafting a claim, re-run verification: does the file still say this? Does this command still exist? Is this version still current? If memory-backed, verify per `auto memory` → "Before recommending from memory".

---

## Non-negotiable checks

Before asserting any of the following, run the cited verification:

| Claim type | Required verification |
|------------|----------------------|
| "File `X` exists" / "edit this file" | `Read` or `Glob` — not recall |
| "Function `foo()` does Y" | `Read` the file; grep for callers if claiming behavior across modules |
| "CLI flag `--bar`" / "env var `BAZ`" | `--help`, code grep, or `context7` for library CLIs |
| "Library `X` has API `Y`" | `context7` — your training data may be stale |
| "Commit `abc123` did Z" / "merged yesterday" | `git log` / `git show` |
| "Test/lint/build passes" | Run the command — see `skills/verification-before-completion` |
| "Evidence in `/tmp/foo.log`" (in subagent returns) | Dispatcher `Read`s the path; missing path → treat as BLOCKED |

---

## Red flags (you are about to hallucinate)

- You can't name the file or line you're quoting from.
- You're about to write a function name, flag, or path you haven't seen in this session.
- You're answering "does this library do X?" from memory without `context7`.
- You're summarizing a file you haven't `Read` in this session.
- A subagent returned an evidence path — you're about to accept it without `Read`ing.
- You're describing "recent changes" without `git log`.

**Any red flag → stop. Verify or say "I don't know."**

---

## Interaction with existing rules

- **`rules/subagent-return-format.md`** — every cited evidence path must exist; dispatchers verify by `Read`. Fabricated paths = BLOCKED, regardless of claimed status.
- **`skills/grounding-claims`** — same principle in skill form; on-demand companion to this always-loaded rule. If the two ever drift, this rules file wins.
- **`skills/verification-before-completion`** — applies to success claims; this rule applies to *any* factual claim.
- **`skills/systematic-debugging`** — applies to bug investigation; this rule forbids bypassing root-cause with a fabricated "typical fix".
- **Memory** — "Before recommending from memory" in the auto memory system is the specific case of tactic 7 (iterative refinement) for cross-session facts.

---

## The bottom line

If you cannot cite the source, you cannot make the claim. "I don't know" is a first-class answer.
