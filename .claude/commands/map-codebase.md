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
HAS_GIT=$(git rev-parse --is-inside-work-tree 2>/dev/null || echo false)
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

If `HAS_MAPS=true`, reuse an explicit Refresh/Update/Skip choice from this task; otherwise ask once:

```
.planning/codebase/ already exists. What would you like to do?
1. Refresh — build replacement maps, validate, then replace only owned map files
2. Update — keep existing, only re-run specific documents
3. Skip — use existing map as-is and exit
```

- **Refresh:** retain existing maps while building replacements in the temporary output directory below; continue to step 3
- **Update:** ask which docs to refresh, then spawn only the relevant mapper(s)
- **Skip:** exit the command

For a refresh/update or initial map, create a new output directory and capture original hashes for the specific final map files that may be replaced:

```bash
mkdir -p .planning
MAP_OUTPUT_DIR=$(mktemp -d .planning/codebase-refresh.XXXXXX)
```

Pass the resolved `MAP_OUTPUT_DIR` to every mapper. Preserve other files and edits. Resolve `commit_docs` before any commit: reuse the task's explicit choice or `.planning/config.json` when present. If unset, ask once whether to commit these maps; default to leaving them uncommitted when no choice is available. `commit_docs=false` forbids planning commits.

### 3. Spawn 4 parallel mapper agents

**Dispatch the independent useful mapper branches concurrently** using the available Agent tool (`Task` alias supported), with `subagent_type="codebase-mapper"`. For Update, dispatch only selected owners. Follow `~/.claude/rules/dispatch-prompt-contract.md` and `rules/subagent-return-format.md`; include output ownership, safe diagnostics and a confirmation/evidence contract. If independent agents are unavailable, report the limitation and use an authorized local fallback without claiming independent review.

Expand the user-supplied focus hint (if any) into a single line: `Focus hint: $ARGUMENTS`. Otherwise omit that line.

**Agent 1 — tech**
```
description: "Map codebase tech stack"
prompt: |
  Focus: tech

  Analyze this codebase for technology stack and external integrations.

  Write these documents to <MAP_OUTPUT_DIR>/ (UPPERCASE filenames):
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

  Write these documents to <MAP_OUTPUT_DIR>/ (UPPERCASE filenames):
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

  Write these documents to <MAP_OUTPUT_DIR>/ (UPPERCASE filenames):
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

  Write this document to <MAP_OUTPUT_DIR>/ (UPPERCASE filename):
  - CONCERNS.md — tech debt, bugs, security, performance, fragile areas, coverage gaps

  Use the template at:
  - ~/.claude/planning-templates/codebase/concerns.md

  Date: <TODAY>
  <optional: Focus hint: ...>

  Explore thoroughly. Write the document directly. Return confirmation only.
```

### 4. Collect confirmations

Wait for selected agents. Each returns a concise confirmation with status, existing evidence paths and required exit-criteria checks, for example:
```
## Mapping Complete
**Focus:** tech
**Documents written:**
- `.planning/codebase/STACK.md` (N lines)
- `.planning/codebase/INTEGRATIONS.md` (N lines)
```

Receive compact confirmations and inspect cited evidence/necessary document regions. If an agent fails, report the missing maps and continue independent verification; do not replace their previous maps or call the full map complete.

### 5. Verify

```bash
ls -la "$MAP_OUTPUT_DIR"/
wc -l "$MAP_OUTPUT_DIR"/*.md
```

All requested documents must exist and contain their template sections with supported file references. Line counts are a size diagnostic, not a completeness proof. Check cited paths and the necessary content; flag missing/empty/unsupported sections.

### 6. Secret scan (mandatory)

Before committing, scan the generated docs for leaked secrets:

```bash
SECRETS=error
if grep -El '(sk-[a-zA-Z0-9]{20,}|sk_live_[a-zA-Z0-9]+|sk_test_[a-zA-Z0-9]+|ghp_[a-zA-Z0-9]{36}|gho_[a-zA-Z0-9]{36}|glpat-[a-zA-Z0-9_-]+|AKIA[A-Z0-9]{16}|xox[baprs]-[a-zA-Z0-9-]+|-----BEGIN.*PRIVATE KEY|eyJ[a-zA-Z0-9_-]+\.eyJ[a-zA-Z0-9_-]+\.)' "$MAP_OUTPUT_DIR"/*.md; then
  SECRETS=true
else
  SCAN_STATUS=$?
  if [ "$SCAN_STATUS" -eq 1 ]; then
    SECRETS=false
  else
    echo "Secret scan failed; stop before publishing or committing." >&2
    exit 1
  fi
fi
```

If `SECRETS=true`:
```
⚠️  Potential secrets detected in codebase docs. Review and redact before committing.
[show affected filenames only; never matched secret values]

Redact confirmed leaks and rescan. If a hit is a reviewed false positive, explain using safe metadata and retain the explicit "safe to proceed" checkpoint.
```
Pause publishing/committing while a confirmed leak, failed scan or unreviewed hit remains. A confirmed secret cannot be waived; after a clean rescan no extra confirmation is needed. Retain the explicit user confirmation for a proposed false-positive waiver.

### 7. Publish validated maps, then commit if enabled

After all requested replacements validate, compare final map hashes with the captured originals. If a user/other-agent edit appeared, preserve it and report the conflict. Back up replaced map files, then replace only those owned validated files; retain unrelated documents. Do not delete the previous directory first. Report a partial failure accurately and keep recoverable originals.

Before **every** planning commit, recheck `HAS_GIT=true` and `commit_docs=true`, inspect the staged diff and stage only the generated owned files. Do not include unrelated staged work. A commit opt-out leaves artifacts available locally. If enabled:
```bash
if [ "$(git rev-parse --is-inside-work-tree 2>/dev/null)" = true ] && [ "${commit_docs:-false}" = true ]; then
  git add -- <validated-owned-map-paths>
  git commit -m "docs: map existing codebase"
fi
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
- [ ] Useful independent mapper branches completed; any agent/runtime limitation is explicit
- [ ] All requested maps written, inspected and safely published; full map claims require all 7 documents
- [ ] Secret scan succeeded and leaks were redacted; only reviewed false positives may use the explicit checkpoint
- [ ] Committed only if Git is available and commit_docs=true; otherwise left uncommitted as requested
- [ ] User offered next step
