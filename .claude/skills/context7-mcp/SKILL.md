---
name: context7-mcp
description: Use for uncertain or version-sensitive library, framework and API details; prefer configured Context7 when available and accept relevant primary/local sources
---

Follow `~/.claude/rules/context7.md`: prefer the configured Context7 tools when available for version-sensitive details. Current official docs, installed help and local source are valid alternatives when they answer the question or Context7 is unavailable. Do not require a redundant network call for ordinary code reading or reuse of valid inspected evidence.

## When to Use This Skill

Activate this skill when the user:

- Asks setup or configuration questions ("How do I configure Next.js middleware?")
- Requests code involving libraries ("Write a Prisma query for...")
- Needs API references ("What are the Supabase auth methods?")
- Needs an uncertain, load-bearing API/version detail while working with a framework

## How to Fetch Documentation

### Step 1: Resolve the Library ID

When the configured tool exposes `resolve-library-id`, call it with:

- `libraryName`: The library name extracted from the user's question
- `query`: What to look up in the library's documentation (improves relevance ranking)

### Step 2: Select the Best Match

From the resolution results, choose based on:

- Exact or closest name match to what the user asked for
- Higher benchmark scores indicate better documentation quality
- If the user mentioned a version (e.g., "React 19"), prefer version-specific IDs

### Step 3: Fetch the Documentation

When the configured tool exposes `query-docs`, call it with:

- `libraryId`: The selected Context7 library ID (e.g., `/vercel/next.js`)
- `query`: What to look up in the library's documentation, scoped to a single concept

If the user's question spans multiple distinct concepts (e.g. routing and auth and caching), make a separate `query-docs` call per concept with the same library ID, unless the question is about how the concepts interact — combined queries dilute ranking and return shallow results for each topic.

### Step 4: Use the Documentation

Incorporate the fetched documentation into your response:

- Answer the user's question using current, accurate information
- Include relevant code examples from the docs
- Cite the primary source/library version when relevant
- Verify installed/project version from manifests, lockfiles or local help; an indexed latest version may differ
- Reuse resolved IDs/results for the same applicable version/concept; rerun when stale or uncertain
- If Context7 is unavailable, unindexed or quota-limited, use official docs or installed help/source. State unknowns rather than inventing a result
- Do not install/download a CLI or authenticate merely to perform a routine lookup; use an already configured option within scope
- Send only a minimal generic documentation query; never credentials, PII or proprietary payloads/code

## Guidelines

- **Be specific**: Describe what to look up in the library's documentation, but keep each query to a single concept
- **One topic per query**: Split multi-topic questions into separate `query-docs` calls — resolve the library ID once, then query per concept, unless the question is about how the concepts interact
- **Version awareness**: When users mention versions ("Next.js 15", "React 19"), use version-specific library IDs if available from the resolution step
- **Prefer official sources**: When multiple matches exist, prefer official/primary packages over community forks
