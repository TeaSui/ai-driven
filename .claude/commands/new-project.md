---
description: Initialize a new project through questioning, optional research, requirements and roadmap; write .planning artifacts and commit each approved stage only when commit_docs is enabled
argument-hint: "[--auto] [@idea.md]"
---

# /new-project

## Objective

Initialize a project through a unified flow: **questioning → research (optional) → requirements → roadmap**. This is the most leveraged moment in any project — deep questioning here means better plans, better execution, better outcomes.

**Creates:**
- `.planning/PROJECT.md` — project context
- `.planning/config.json` — workflow preferences
- `.planning/research/` — domain research (optional)
- `.planning/REQUIREMENTS.md` — scoped requirements with REQ-IDs
- `.planning/ROADMAP.md` — phase structure
- `.planning/STATE.md` — project memory

After this command: run the planning command for Phase 1.

## Supporting files

Read these before running:
- Questioning techniques: `~/.claude/planning-references/questioning.md`
- UI/brand tone (banners, section dividers): `~/.claude/planning-references/ui-brand.md`
- Templates you will fill in:
  - `~/.claude/planning-templates/project.md`
  - `~/.claude/planning-templates/requirements.md`
  - `~/.claude/planning-templates/roadmap.md`
  - `~/.claude/planning-templates/state.md`
  - `~/.claude/planning-templates/research/{STACK,FEATURES,ARCHITECTURE,PITFALLS,SUMMARY}.md`

## Arguments

- `$ARGUMENTS` — may contain `--auto` flag and/or an `@file` reference.
- **Auto mode** (`--auto` present): skip deep questioning, skip approval gates, expect an idea document (`@file` or pasted text) to synthesize from.
  - If no idea document is supplied with `--auto`, print this error and stop:
    ```
    Error: --auto requires an idea document.

    Usage:
      /new-project --auto @your-idea.md
      /new-project --auto [paste or write your idea here]
    ```

## Process

### 1. Preflight

```bash
# Detect state
HAS_GIT=$(git rev-parse --is-inside-work-tree 2>/dev/null || echo false)
PROJECT_EXISTS=$( [ -f .planning/PROJECT.md ] && echo true || echo false )
HAS_CODEBASE_MAP=$( [ -d .planning/codebase ] && echo true || echo false )
HAS_CODE=$(find . -maxdepth 3 \( -name '*.ts' -o -name '*.tsx' -o -name '*.js' -o -name '*.py' -o -name '*.go' -o -name '*.rs' -o -name '*.java' -o -name '*.swift' \) 2>/dev/null | grep -v node_modules | grep -v .git | head -1)
HAS_PKG=$( { [ -f package.json ] || [ -f requirements.txt ] || [ -f Cargo.toml ] || [ -f go.mod ] || [ -f pyproject.toml ] || [ -f pom.xml ]; } && echo true || echo false )
[ -n "$HAS_CODE" ] || [ "$HAS_PKG" = "true" ] && IS_BROWNFIELD=true || IS_BROWNFIELD=false
TODAY=$(date +%F)
echo "project_exists=$PROJECT_EXISTS has_git=$HAS_GIT has_codebase_map=$HAS_CODEBASE_MAP is_brownfield=$IS_BROWNFIELD today=$TODAY"
```

- If `PROJECT_EXISTS=true`: stop. Tell the user the project is already initialized and suggest editing `.planning/PROJECT.md` directly.
- `git rev-parse` supports linked worktrees/subdirectories; do not infer repository state from whether `.git` is a directory.
- Resolve `commit_docs` **before the first commit**: reuse the task's explicit choice or existing `.planning/config.json`; otherwise ask once whether to commit planning docs. In `--auto`, use a supplied preference; if none exists, default to leaving artifacts uncommitted rather than inventing consent to repository/history changes.
- If `HAS_GIT=false`, initialize Git only when repository creation is within the user's authorization. Recheck `HAS_GIT` afterward. Planning artifacts can be written without a repository.
- Before **every** planning commit, require `HAS_GIT=true` and `commit_docs=true`, inspect the staged diff, stage only owned planning paths, and avoid including unrelated staged work. No opt-out can be overridden by `--auto` or a later checkpoint.
- Follow `~/.claude/rules/workflow-routing.md` for planning/dispatch scope and prerequisite order; research does not authorize implementation or external writes.

### 2. Brownfield offer (skip in auto mode)

If `IS_BROWNFIELD=true` AND `HAS_CODEBASE_MAP=false`:

```
Existing code detected but no codebase map at .planning/codebase/.

Options:
1. Map the codebase first (recommended) — run /clear then /map-codebase, then return to /new-project
2. Skip mapping — continue without codebase context
```

If user picks "map first": exit this command. Otherwise continue.

### 3. Deep questioning (skip in auto mode)

Display stage banner:

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 ► QUESTIONING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

Use the existing idea/session context first. Ask **"What do you want to build?"** only if the goal is missing.

Then follow threads based on the response. Use techniques from `~/.claude/planning-references/questioning.md`:
- Challenge vagueness — "what do you mean by X?"
- Make abstract concrete — "can you give me an example?"
- Surface assumptions — "what would have to be true for this to work?"
- Find edges — "when shouldn't this happen?"
- Reveal motivation — "why this, why now?"

Mentally track the context checklist (problem, user, success, constraints, key decisions). Weave in questions naturally rather than interrogating.

**Checkpoint:** preserve any explicit user request to review before writing. If no such checkpoint exists and the user has already delegated project initialization, write PROJECT.md when the context is sufficient. Otherwise ask once whether to create it or keep exploring. Reuse a prior "go" for the same scope; do not restart approval loops.

### 4. Write PROJECT.md

Read the template at `~/.claude/planning-templates/project.md` and fill it in with everything you've gathered.

**Greenfield:** Requirements → Active = hypotheses; Validated = (None yet — ship to validate).
**Brownfield (codebase map exists):** read `.planning/codebase/ARCHITECTURE.md` and `STACK.md`, treat existing capabilities as Validated; new asks go into Active.

Footer line:
```markdown
---
*Last updated: <TODAY> after initialization*
```

Create `.planning/` before writing. Commit only if `HAS_GIT=true` and `commit_docs=true`:
```bash
if [ "$(git rev-parse --is-inside-work-tree 2>/dev/null)" = true ] && [ "${commit_docs:-false}" = true ]; then
  git add -- .planning/PROJECT.md
  git commit -m "docs: initialize project"
fi
```

### 5. Workflow config

Resolve the remaining config choices, reusing explicit preferences. Ask only missing choices in interactive mode; `--auto` uses documented defaults while preserving the earlier commit_docs decision.

| Question | Options |
|---|---|
| **Mode** — how do you want to work? | `yolo` (auto-approve, ship fast) / `interactive` (confirm each step) |
| **Depth** — how thorough should planning be? | `quick` (3-5 phases) / `standard` (5-8) / `comprehensive` (8-12) |
| **Parallelization** — run independent plans concurrently? | true / false |
| **Git tracking** — commit planning docs? | Reuse commit_docs resolved in preflight; do not ask again |

Then 3 workflow-agent questions:

| Agent | Default | What it does |
|---|---|---|
| **Researcher** — research each phase before planning? | yes | surfaces domain patterns + gotchas |
| **Plan checker** — verify plans will achieve their goals? | yes | catches gaps before execution |
| **Verifier** — verify work satisfies requirements after each phase? | yes | confirms deliverables |

Write `.planning/config.json` using the resolved preferences. Set `commit_docs` to the boolean resolved in preflight; the illustrative value below must never override an explicit opt-out:

```json
{
  "mode": "yolo|interactive",
  "depth": "quick|standard|comprehensive",
  "parallelization": true,
  "commit_docs": false,
  "workflow": {
    "research": true,
    "plan_check": true,
    "verifier": true,
    "auto_advance": false
  }
}
```

If `commit_docs=false`, make no planning commit. Add `.planning/` to `.gitignore` only when ignoring these local artifacts is also desired and compatible with existing tracking; preserve existing ignore rules and do not untrack files automatically.

Commit config (only if `commit_docs=true` and `HAS_GIT=true`):
```bash
if [ "$(git rev-parse --is-inside-work-tree 2>/dev/null)" = true ] && [ "${commit_docs:-false}" = true ]; then
  git add .planning/config.json
  git commit -m "chore: add project config"
fi
```

### 6. Research (optional)

Ask (skip in auto mode — default to yes):
```
Research the domain ecosystem before defining requirements?
- Yes (recommended) — discover standard stacks, expected features, pitfalls
- No — go straight to requirements (I know this domain well)
```

If yes, display banner:
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 ► RESEARCHING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

`mkdir -p .planning/research`

Determine context: if PROJECT.md has no Validated requirements → **greenfield** (building from scratch); otherwise → **subsequent milestone** (adding to existing app).

**Spawn 4 `general-purpose` research agents in parallel** (one assistant message, four Task calls). Each fills a different research template.

Common preamble for every agent:
```
You are a domain researcher. Explore the current 2026 state of the art for this domain and write ONE document directly to .planning/research/. Use current official docs and Context7 where available. Do not rely only on training data.

Include file paths (where relevant), current library versions, and confidence levels. Return ~10 lines of confirmation after writing — do NOT paste document contents back.

<files_to_read>
- .planning/PROJECT.md  (project context and goals)
</files_to_read>

Date: <TODAY>
Milestone context: <greenfield | subsequent>
```

Then per-agent specifics:

**Agent 1 — Stack** → writes `.planning/research/STACK.md` using template `~/.claude/planning-templates/research/STACK.md`. Question: *"What's the standard 2026 stack for [domain]?"* Downstream: feeds roadmap. Be prescriptive: specific libraries + versions, rationale, what NOT to use.

**Agent 2 — Features** → writes `.planning/research/FEATURES.md` using template `~/.claude/planning-templates/research/FEATURES.md`. Question: *"What features do [domain] products have? Table stakes vs differentiating vs anti-features?"* Downstream: feeds requirements scoping.

**Agent 3 — Architecture** → writes `.planning/research/ARCHITECTURE.md` using template `~/.claude/planning-templates/research/ARCHITECTURE.md`. Question: *"How are [domain] systems typically structured? Major components, data flow, build order?"* Downstream: informs phase structure.

**Agent 4 — Pitfalls** → writes `.planning/research/PITFALLS.md` using template `~/.claude/planning-templates/research/PITFALLS.md`. Question: *"What do [domain] projects commonly get wrong?"* For each pitfall: warning signs, prevention, which phase should address.

After all 4 complete, **spawn a synthesizer** (`general-purpose`, sequential, not parallel):
```
Synthesize .planning/research/STACK.md + FEATURES.md + ARCHITECTURE.md + PITFALLS.md
into .planning/research/SUMMARY.md using template ~/.claude/planning-templates/research/SUMMARY.md.

Extract: recommended stack, table-stakes features, key architectural decisions, top pitfalls.
Return ~10 lines of confirmation after writing.
```

Commit only if `HAS_GIT=true` and `commit_docs=true`:
```bash
if [ "$(git rev-parse --is-inside-work-tree 2>/dev/null)" = true ] && [ "${commit_docs:-false}" = true ]; then
  git add .planning/research/
  git commit -m "docs: research [domain]"
fi
```

Display summary banner:
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 ► RESEARCH COMPLETE ✓
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Stack:        [from SUMMARY.md]
Table stakes: [from SUMMARY.md]
Watch out:    [from SUMMARY.md]

Files: .planning/research/
```

### 7. Requirements

Banner:
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 ► DEFINING REQUIREMENTS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

Load context: PROJECT.md (core value + constraints + scope); if research exists, `.planning/research/FEATURES.md`.

**Auto mode:** auto-include all table-stakes + anything mentioned in the idea doc; defer unmentioned differentiators; skip approval gate.

**Interactive mode:** present features by category (Authentication, Content, etc). For each category, ask:
```
Which [category] features are in v1?
- [feature 1] — [brief description]
- [feature 2] — [brief description]
- None for v1 — defer the whole category
```
Track: selected → v1, unselected table-stakes → v2, unselected differentiators → out of scope.

Ask once: *"Any requirements research missed? (Features specific to your vision)"* — capture any additions.

Cross-check v1 against the Core Value from PROJECT.md. Surface gaps.

**Write REQUIREMENTS.md** using template `~/.claude/planning-templates/requirements.md`:
- v1 requirements grouped by category, with REQ-IDs `[CATEGORY]-[NN]` (AUTH-01, CONTENT-02)
- v2 requirements (deferred)
- Out of scope (with reasoning)
- Traceability section (empty — roadmapper fills it)

**Good requirements are:**
- Specific + testable: `"User can reset password via email link"` (not `"Handle password reset"`)
- User-centric: `"User can X"` (not `"System does Y"`)
- Atomic: one capability per requirement
- Independent: minimal dependencies

Reject vague ones and push back.

In interactive mode, show the full list. Preserve an explicit requirements-review checkpoint; otherwise reuse existing delegation and ask only when a substantive scope choice remains.

Commit only if `HAS_GIT=true` and `commit_docs=true`:
```bash
if [ "$(git rev-parse --is-inside-work-tree 2>/dev/null)" = true ] && [ "${commit_docs:-false}" = true ]; then
  git add -- .planning/REQUIREMENTS.md
  git commit -m "docs: define v1 requirements"
fi
```

### 8. Roadmap

Banner:
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 ► CREATING ROADMAP
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

Spawn ONE `general-purpose` agent for roadmapping. Prompt:

```
You are a roadmap architect. Produce a phased roadmap from project + requirements.

<files_to_read>
- .planning/PROJECT.md           (project context)
- .planning/REQUIREMENTS.md      (v1 requirements with REQ-IDs)
- .planning/research/SUMMARY.md  (if exists)
- .planning/config.json          (depth and mode)
</files_to_read>

<task>
1. Derive phases from requirements — don't impose a predetermined structure.
2. Map EVERY v1 REQ-ID to exactly one phase (100% coverage required).
3. Derive 2-5 success criteria per phase, phrased as observable user behaviors.
4. Depth target from config.json: quick=3-5 phases, standard=5-8, comprehensive=8-12.
5. Write files immediately (don't return content):
   - .planning/ROADMAP.md  (use template ~/.claude/planning-templates/roadmap.md)
   - .planning/STATE.md    (use template ~/.claude/planning-templates/state.md — initial state, phase 1 pending)
   - Update .planning/REQUIREMENTS.md traceability section to map each REQ-ID to its phase.
6. Return in this format only:

## ROADMAP CREATED
- phases: <N>
- requirements mapped: <X>/<X>
- files written: ROADMAP.md, STATE.md, REQUIREMENTS.md (traceability updated)

If you cannot create a valid roadmap (e.g., requirements are contradictory), return:
## ROADMAP BLOCKED
- reason: ...
- questions for user: ...
</task>
```

**If blocker returned:** surface it to the user, resolve, re-spawn.

**If created:** read the generated ROADMAP.md and render it inline as a phase table (#, Name, Goal, Requirements, # of success criteria), followed by per-phase detail.

**Auto mode:** accept the in-scope roadmap and commit only if Git/commit_docs conditions hold.

**Interactive mode:** preserve an explicit roadmap checkpoint. If the user already delegated this decision for the same scope, present the roadmap and continue; otherwise ask:
```
Does this roadmap structure work for you?
- Approve — continue and commit only if Git tracking is enabled
- Adjust — tell me what to change (I'll re-spawn the roadmapper)
- Review full file — show raw ROADMAP.md
```
On Adjust: capture notes and re-dispatch with a `<revision>` block referencing `.planning/ROADMAP.md`. Respect `~/.claude/rules/review-loop-limits.md` for agent review/dispatch rounds; unresolved scope decisions go back to the user rather than an unbounded loop.

Commit only if `HAS_GIT=true` and `commit_docs=true`:
```bash
if [ "$(git rev-parse --is-inside-work-tree 2>/dev/null)" = true ] && [ "${commit_docs:-false}" = true ]; then
  git add -- .planning/ROADMAP.md .planning/STATE.md .planning/REQUIREMENTS.md
  git commit -m "docs: create roadmap (<N> phases)"
fi
```

### 9. Done

Completion summary:

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 ► PROJECT INITIALIZED ✓
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

<Project Name>

| Artifact     | Location                    |
|--------------|-----------------------------|
| Project      | .planning/PROJECT.md        |
| Config       | .planning/config.json       |
| Research     | .planning/research/         |
| Requirements | .planning/REQUIREMENTS.md   |
| Roadmap      | .planning/ROADMAP.md        |
| State        | .planning/STATE.md          |

<N> phases | <X> requirements | Ready to build ✓

▶ Next up
  Phase 1: <Phase Name> — <Goal>
  Start with a planning pass for phase 1.
  (Run /clear first → fresh context window)
```

## Success criteria

- [ ] `.planning/` directory created
- [ ] Repository state detected correctly; Git initialized only if authorized, otherwise artifacts remain local
- [ ] Brownfield detection ran; user offered `/map-codebase` when appropriate
- [ ] Deep questioning completed (unless `--auto`)
- [ ] `PROJECT.md` captures full context → committed only when Git/commit_docs enable it
- [ ] `config.json` has mode, depth, parallelization, workflow flags → committed only when Git/commit_docs enable it
- [ ] Research completed if selected (4 parallel agents + 1 synthesizer) → committed only when Git/commit_docs enable it
- [ ] Requirements gathered with REQ-IDs; user scoped each category (interactive) → committed only when Git/commit_docs enable it
- [ ] Roadmap agent spawned; ROADMAP.md + STATE.md + updated REQUIREMENTS.md written → committed only when Git/commit_docs enable it
- [ ] Explicit user roadmap checkpoint honored, or existing delegation/auto mode reused
- [ ] User knows the next step

**Persistence:** save artifacts after each stage. Commit only with Git available and commit_docs=true, checking that condition before every commit. Keep artifacts available when tracking is disabled.
