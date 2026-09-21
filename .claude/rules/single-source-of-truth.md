# Single Source of Truth (No Harmful Duplication)

**Scope:** Authoring or editing any instruction / reference artifact — skills, `docs/`, rule files, `CLAUDE.md`, READMEs, ADRs, agent definitions. Governs when you may restate content that already lives in another file.

## The litmus test (primary criterion)

Before copying or restating content from another file, ask:

> **"If the source file changes, must I edit this copy to keep it correct?"**
> - **Yes → harmful duplication.** Do not copy. Point to the source instead.
> - **No → acceptable.** A pointer, cross-reference, or illustrative naming is fine.

This test **outranks the "it's just a curated subset" justification.** A curated subset of a rule/doc body still fails the test — when the source changes, the subset goes stale. "Curated subset" is not a license to duplicate; the sync-burden test is the deciding line. Decide with the litmus test first; reach for "curated subset" only to describe content that already passes it.

## Acceptable inline

- **Pointers / cross-references:** "`X` applies — see `path/to/source`." Editing the source never breaks the pointer.
- **Illustrative naming with no completeness contract:** list a few item *names* followed by "…and the rest", explicitly delegating authority to the source (and any enforcing tool). No "this is the full list" promise → no sync burden.
- **Domain specialization that ADDS value:** instantiate an abstract rule for the local context (e.g. name which decisions are the high-risk ambiguity points here, or which concrete source types to cite). This carries information the source does not.

## Harmful (do not do)

- Verbatim or near-verbatim copies of a rule/doc body in a second file.
- Restating a checklist a **tool already enforces** (e.g. duplicating lint rules a linter checks). Point to the tool; do not hand-maintain a parallel list that silently drifts from it.
- "Standalone for portability" copies of content that is itself always loaded/available in the relevant context — that pays a real maintenance cost for a portability benefit you don't need. Drop the copy; keep the pointer.

## When a duplicate is genuinely unavoidable

Rare. If you must restate, add a provenance marker so drift is detectable — `synced from <source> as of <date/SHA>` — and treat it under the staleness checks in `strategic-output-persistence.md`.

## Interaction with existing rules

- **`strategic-output-persistence.md`** — its staleness check is the same concern for `docs/`; this rule is the upstream "don't create the duplicate in the first place".
- **`reduce-hallucinations.md`** — a stale duplicate is a future false claim (it asserts what the source no longer says). Removing the duplicate removes the drift before it can mislead.
- **`CLAUDE.md`** — already states "Keep it as a pointer index, not a duplication of the rule files." This rule generalizes that to every artifact.
