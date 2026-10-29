---
name: find-docs
description: Retrieve primary or installed documentation for uncertain or version-sensitive library, framework, SDK, CLI and cloud-service details; prefer configured Context7 and disclose missing evidence
---

# Documentation Lookup

Follow `~/.claude/rules/context7.md` and `~/.claude/rules/reduce-hallucinations.md`. Verify load-bearing syntax, flags, configuration and version-specific behavior against applicable primary sources. Ordinary code reading/refactoring does not require a network lookup when the relevant behavior is established by local source.

## 1. Identify the question and version

- Read manifests, lockfiles, installed help or project constraints to identify the version actually in use. Do not silently substitute the latest release for the project's version.
- Form a specific query for one concept. Split unrelated concepts; combine them when the interaction is the actual question.
- Keep external queries generic. Never send credentials, API keys, PII, proprietary code or request/message payloads.
- Reuse already inspected documentation while it remains applicable to the same version and question. Recheck after relevant version changes or when uncertainty arises.

## 2. Choose an available source

Prefer configured Context7 tools when available. Current official documentation, installed CLI help and local package/source code are valid alternatives when they answer the question or Context7 is unavailable. Do not invent a tool call, returned ID or documentation result.

A Context7 CLI is an optional fallback only when already installed/configured and suitable. Inspect that installed version's help for its actual commands/flags. Do not invoke a package downloader, use `@latest`, install globally or run a login flow merely for a routine lookup. Dependency/auth changes require their own authorized scope; they are not prerequisites to consulting official docs.

## 3. Resolve the library (Context7 route)

Use the available resolver (`resolve-library-id` when exposed by the configured tool) before querying unless a valid, applicable ID is already known from inspected evidence or supplied by the user. Provide the library name and a focused query.

Select a result using:

- Exact package/product identity and description relevance
- Primary/official source rather than a similarly named fork
- Documentation coverage and source reputation
- Available versions matching the required project version

An ID has a form such as `/org/project` or a version-specific identifier returned by the resolver. Never invent a version suffix. If the exact version is not indexed, use version-matched official docs or local source; label any compatibility inference explicitly.

If results are ambiguous, inspect the project context first. Ask only when the ambiguity materially changes the answer; continue independent work while clarification is pending.

## 4. Query and inspect

Use the available documentation query tool (`query-docs` when exposed), passing the resolved ID and focused question. One resolution can support several related queries.

| Useful query | Too vague/broad |
|---|---|
| React effect cleanup for an asynchronous operation | hooks |
| The installed CLI's file-selection flag | test |
| Transaction isolation options for the project's database adapter version | database and auth and caching |

Limit repeated attempts: after about three unproductive queries, change source or refine the question rather than repeating the same lookup. Read the relevant result in context; snippets can omit version constraints or prerequisites.

## 5. Answer with evidence

- Cite the official source link or installed help/source location and relevant version.
- Distinguish documented facts, local behavior and compatibility inferences.
- Use examples only for the applicable version; state any unexecuted example's validation limits.
- If Context7 is unavailable, quota-limited or missing the package, use official docs, installed help or local source. Briefly disclose the substitution when it matters to confidence.
- If no adequate source can establish a load-bearing fact, say what is unknown and what would resolve it. Training recollection may help form a search query; it must not masquerade as verified current documentation.

## Authentication and errors

Use the existing configured connection. Do not request or print API keys, dump environment/config values or initiate login automatically. A quota error does not require a new account or payment: choose an available primary/local source first. Report a source failure accurately without exposing confidential response content.
