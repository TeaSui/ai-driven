---
description: Analyze codebase with parallel mapper agents to produce .planning/codebase/ documents (STACK, INTEGRATIONS, ARCHITECTURE, STRUCTURE, CONVENTIONS, TESTING, CONCERNS)
argument-hint: "[optional: focus area hint, e.g. 'api' or 'auth']"
---

# /map-codebase

## Objective

Run 4 parallel `codebase-mapper` agents that explore this codebase and write 7 structured documents to `.planning/codebase/`. The orchestrator (you) receives confirmations only — never document contents — keeping context usage minimal.

**Optional focus hint:** `$ARGUMENTS` — if provided, pass it to agents so they bias their exploration toward that subsystem.

## When to use

Use for:
- Brownfield projects before `/new-project`, so PROJECT.md can build on real context
- Refreshing the codebase map after significant changes
- Onboarding into an unfamiliar codebase
- Before a major refactor

Skip for: greenfield projects with no code yet, trivial codebases (<5 files).

## Process

### 1. Preflight

Run this single block before anything else:

```bash
# Detect git, existing maps, and date
HAS_GIT=$( [ -d .git ] && echo true || echo false )
CODEBASE_DIR=".planning/codebase"
if [ -d "$CODEBASE_DIR" ]; then
  HAS_MAPS=true
  ls -la "$CODEBASE_DIR"
else
  HAS_MAPS=false
fi
TODAY=$(date +%F)
echo "has_git=$HAS_GIT has_maps=$HAS_MAPS today=$TODAY"
```

### 2. Handle existing maps

If `HAS_MAPS=true`, ask the user inline:

```
.planning/codebase/ already exists. What would you like to do?
1. Refresh — delete and remap everything
2. Update — keep existing, only re-run specific documents
3. Skip — use existing map as-is and exit
```

- **Refresh:** `rm -rf .planning/codebase && mkdir -p .planning/codebase`, continue to step 3
- **Update:** ask which docs to refresh, then spawn only the relevant mapper(s)
- **Skip:** exit the command

If `HAS_MAPS=false`: `mkdir -p .planning/codebase` and continue.

### 3. Spawn 4 parallel mapper agents

**Emit all 4 Task tool calls in a single assistant message** so they run concurrently. Use `subagent_type="codebase-mapper"`. Each agent writes documents directly — you never see the contents.

Expand the user-supplied focus hint (if any) into a single line: `Focus hint: $ARGUMENTS`. Otherwise omit that line.

**Agent 1 — tech**
```
description: "Map codebase tech stack"
prompt: |
  Focus: tech

  Analyze this codebase for technology stack and external integrations.

  Write these documents to .planning/codebase/ (UPPERCASE filenames):
  - STACK.md   — languages, runtime, frameworks, dependencies, configuration
  - INTEGRATIONS.md — external APIs, databases, auth, webhooks, deployment

  Use the templates at:
  - ~/.claude/planning-templates/codebase/stack.md
  - ~/.claude/planning-templates/codebase/integrations.md

  Date: <TODAY>
  <optional: Focus hint: ...>

  Explore thoroughly. Write documents directly. Return confirmation only (~10 lines).
```

**Agent 2 — arch**
```
description: "Map codebase architecture"
prompt: |
  Focus: arch

  Analyze this codebase's architecture and directory structure.

  Write these documents to .planning/codebase/ (UPPERCASE filenames):
  - ARCHITECTURE.md — pattern, layers, data flow, abstractions, entry points
  - STRUCTURE.md    — directory layout, key locations, naming, where to add code

  Use the templates at:
  - ~/.claude/planning-templates/codebase/architecture.md
  - ~/.claude/planning-templates/codebase/structure.md

  Date: <TODAY>
  <optional: Focus hint: ...>

  Explore thoroughly. Write documents directly. Return confirmation only.
```

**Agent 3 — quality**
```
description: "Map codebase conventions + tests"
prompt: |
  Focus: quality

  Analyze this codebase's coding conventions and testing patterns.

  Write these documents to .planning/codebase/ (UPPERCASE filenames):
  - CONVENTIONS.md — naming, style, imports, error handling, logging
  - TESTING.md     — framework, file organization, mocking, fixtures, coverage

  Use the templates at:
  - ~/.claude/planning-templates/codebase/conventions.md
  - ~/.claude/planning-templates/codebase/testing.md

  Date: <TODAY>
  <optional: Focus hint: ...>

  Explore thoroughly. Write documents directly. Return confirmation only.
```

**Agent 4 — concerns**
```
description: "Map codebase concerns"
prompt: |
  Focus: concerns

  Analyze this codebase for technical debt, known issues, fragile areas.

  Write this document to .planning/codebase/ (UPPERCASE filename):
  - CONCERNS.md — tech debt, bugs, security, performance, fragile areas, coverage gaps

  Use the template at:
  - ~/.claude/planning-templates/codebase/concerns.md

  Date: <TODAY>
  <optional: Focus hint: ...>

  Explore thoroughly. Write the document directly. Return confirmation only.
```

### 4. Collect confirmations

Wait for all 4 agents. Each returns a ~10-line confirmation like:
```
## Mapping Complete
**Focus:** tech
**Documents written:**
- `.planning/codebase/STACK.md` (N lines)
- `.planning/codebase/INTEGRATIONS.md` (N lines)
```

You receive file paths + line counts only, never document contents. If an agent fails, note it and continue with successful ones.

### 5. Verify

```bash
ls -la .planning/codebase/
wc -l .planning/codebase/*.md
```

All 7 documents should exist and each should have >20 lines. Flag any missing or empty.

### 6. Secret scan (mandatory)

Before committing, scan the generated docs for leaked secrets:

```bash
grep -E '(sk-[a-zA-Z0-9]{20,}|sk_live_[a-zA-Z0-9]+|sk_test_[a-zA-Z0-9]+|ghp_[a-zA-Z0-9]{36}|gho_[a-zA-Z0-9]{36}|glpat-[a-zA-Z0-9_-]+|AKIA[A-Z0-9]{16}|xox[baprs]-[a-zA-Z0-9-]+|-----BEGIN.*PRIVATE KEY|eyJ[a-zA-Z0-9_-]+\.eyJ[a-zA-Z0-9_-]+\.)' .planning/codebase/*.md && SECRETS=true || SECRETS=false
```

If `SECRETS=true`:
```
⚠️  Potential secrets detected in codebase docs. Review and redact before committing.
[show grep output]

Reply "safe to proceed" once redacted, or edit the files first.
```
Pause. Do not continue until the user confirms.

### 7. Commit (if git + user hasn't opted out)

If `HAS_GIT=true`:
```bash
git add .planning/codebase/
git commit -m "docs: map existing codebase"
```

If not a git repo, skip.

### 8. Summary + next steps

Print:
```
Codebase mapping complete.

Created .planning/codebase/:
- STACK.md (<N> lines) — technologies and dependencies
- INTEGRATIONS.md (<N> lines) — external services and APIs
- ARCHITECTURE.md (<N> lines) — system design and patterns
- STRUCTURE.md (<N> lines) — directory layout and organization
- CONVENTIONS.md (<N> lines) — code style and patterns
- TESTING.md (<N> lines) — test structure and practices
- CONCERNS.md (<N> lines) — tech debt and issues

---

▶ Next up
  /new-project — initialize a project using this codebase map as context
  (run /clear first for a fresh context window)
```

## Success criteria

- [ ] `.planning/codebase/` exists
- [ ] 4 mapper agents spawned in parallel (`codebase-mapper` subagent type)
- [ ] All 7 documents written by agents (orchestrator never sees contents)
- [ ] Secret scan clean (or user explicitly confirmed safe)
- [ ] Committed to git (if repo)
- [ ] User offered next step
