---
name: writing-software-docs
description: This skill should be used when the user wants a STRUCTURED, deliverable software document in a recognized industry format — a BRD, PRD, FRD, User Story + Acceptance Criteria, RFC, ADR, Specification, or Concern Report. Triggers on "write a PRD/spec/RFC/ADR", "spec out this feature", "turn these requirements into a doc developers can build", "write up the decision to use X" (→ ADR), or "do a compatibility/concern analysis" (→ Concern Report) — including when the user describes the artifact without naming the type. Use it so the document follows the standard structure that keeps requirements complete, implementable, testable, and codegen-friendly. NOT for code comments, docstrings, READMEs, commit messages, changelogs, chat answers, or quick informal notes — those are plain prose, not structured deliverables; do those directly without this skill.
---

# Writing Software Documents

Business analysts, product managers, architects, and senior engineers don't write deliverable documents free-form. They use standard structures so **no requirement is missed, developers can implement, QA can test, stakeholders can review, and AI can generate code with fewer errors.** Free-form prose silently drops sections; a structure forces every load-bearing question to get an answer (or be marked open).

This skill produces a complete first draft in the correct structure for the document the user needs.

## Step 0 — Is this actually a structured deliverable?

Not every "write up" or "document this" wants one of these documents. If the user wants a code comment, a README section, a commit message, a Slack update, or a quick paragraph, write that prose directly — don't impose a heavyweight template. Use this skill only when the user wants a **named, reusable document** (a PRD, a spec, an ADR…) or clearly describes one. When in doubt, ask one line: "Do you want a full <type>, or just a short write-up?"

## Step 1 — Identify the document type

| The user wants to capture… | Type | Owning role | Reference file |
|---|---|---|---|
| What the business wants and why (objectives, scope, success metrics) | **BRD** | BA, BO, PM | `references/requirements-family.md` |
| Why a product feature exists, how it works, how success is measured | **PRD** | PM, PO | `references/requirements-family.md` |
| Exact functional behavior — inputs, outputs, validation, exceptions | **FRD** | BA, Dev | `references/requirements-family.md` |
| One unit of agile work with testable acceptance criteria | **User Story + AC** | BA | `references/agile-stories.md` |
| A technical proposal up for discussion (system/API/architecture design) | **RFC** | Senior Dev, Architect | `references/architecture-records.md` |
| A single architecture decision and its consequences | **ADR** | Architect | `references/architecture-records.md` |
| A full, implementation-ready feature spec (API, events, schema, tests) | **Specification** | TechLead, Dev, AI agent | `references/specification.md` |
| A contract/compatibility/risk analysis: what exists vs. what's needed | **Concern Report** | Backend team | `references/concern-report.md` |

**Disambiguation rule:** if two types are plausible and the choice changes the *audience* (e.g. PRD vs. FRD, FRD vs. Specification), ask one line. Otherwise pick the best fit, state it ("This is an ADR — writing it now"), and proceed.
- **PRD vs. FRD:** PRD if the reader makes a product/scope decision; FRD if they build exact behavior.
- **FRD vs. Specification:** FRD is behavior described for humans; Specification adds machine contracts (API/event/schema) and is the codegen-ready artifact.
- **RFC vs. ADR:** RFC if the team is still choosing; ADR if the decision is made and you're recording it.

If a request spans several types, produce the one that fits the current need and offer the next as a follow-up — don't dump all of them. They commonly relate as: requirements (BRD/PRD) → decisions (RFC → ADR) → Specification → User Stories, with a Concern Report when assessing a change against what exists. Treat that as a rough map, not a fixed pipeline.

## Step 1.5 — Routing check (this skill formats; it does not bypass routing)

This skill is a **structural layer**, not a substitute for the agent-routing model in `rules/workflow-routing.md` and `rules/agents.md`. Several of these documents are the canonical artifacts of strategic agents: user stories belong to `business-analyst-subagent`, RFC/ADR/Specification to `tech-lead-subagent`, and any threat-model-adjacent analysis to `security-engineer-subagent`.

Before drafting in the main session, check: does the document describe **trust-boundary** work (auth, payments, PII, a new endpoint/webhook, a new external integration — see the `rules/workflow-routing.md` checklist) and the owning agent hasn't run? If so, flag it: the artifact should be produced (or reviewed) by that agent, not improvised here. You can still draft a *working sketch*, but say plainly that it isn't a substitute for the agent's output. Formatting a document never converts trust-boundary work into fast-path work.

Use artifact status labels so readers do not mistake a formatted draft for an approved contract:
- **Working Draft** — written in the main session before required BA/TechLead/Security gates have run.
- **Agent-Reviewed** — reviewed or produced by the owning strategic agent, with evidence paths cited.
- **Approved** — explicitly accepted by the user/team after the required routing gates.

For trust-boundary RFCs, ADRs, Specifications, or Concern Reports, default to **Working Draft** until Security/TechLead ownership is satisfied. Add a short `Routing / Required Review` section near the top naming the missing gate, e.g. `Security review required: new endpoint + PII`.

## Step 2 — Gather context before drafting

A structured shell filled with guesses looks authoritative while being wrong. Ground the draft:

- Read the conversation for stated goals, constraints, and decisions already made.
- Read relevant code, existing `docs/`, and upstream artifacts (a Security threat model, a TechLead contract, BA requirements) so the document is consistent with them.
- For library/API/CLI facts, verify rather than recall (`rules/reduce-hallucinations.md`). If you can't verify, mark it — don't invent.

## Step 3 — Draft the full document

Open the matching reference file, follow its structure exactly, and fill every section with content grounded in context. The reference files carry the section list, ordering, and a worked example per type.

**Two provenance markers — use them instead of fabricating:**

- `[NEEDS INPUT: <what & why it matters>]` — a real gap; the answer isn't anywhere in context. Example: `OCR is invoked on upload. [NEEDS INPUT: which OCR provider — affects the latency SLA]`.
- `[ASSUMED: <inference + basis>]` — you inferred something reasonable from context but the user should confirm it. Example: `Max file size 10MB [ASSUMED: matches the existing upload limit in config/upload.go]`.

**Hard rule on numbers:** any quantitative target, SLA, threshold, percentage, or limit that the user or a source did *not* state MUST carry `[NEEDS INPUT]` or `[ASSUMED]` — never a bare invented number. The worked examples in the reference files show concrete numbers only to illustrate format; they are not a licence to make up metrics. An unmarked "p95 < 2s" or "85% target" that no one specified is a fabrication, and fabrication propagates straight into code and tests.

Collect every `[NEEDS INPUT]` and `[ASSUMED]` into an **Open Questions** section at the end so the reader sees all open decisions at a glance. Number requirements and rules (FR-1, BR-1, AUTH-01…) so downstream docs, tests, and reviews can cite them precisely.

## Step 4 — Deliver (and save only when it's a real deliverable)

By default, return the draft in the conversation. **Save to a file only when** the user asked for a persisted artifact, OR you're running as a strategic subagent whose persistence obligation requires it. Don't silently write files for a casual request — per the environment's "confirm before file generation" expectation, ask first if it's unclear whether the user wants a file.

When you do save, map to the existing `docs/` convention (`rules/strategic-output-persistence.md`):

| Type | Location |
|---|---|
| BRD, PRD, FRD, User Story + AC | `docs/requirements/` |
| RFC, ADR, Specification | `docs/contracts/` |
| Concern Report | `docs/contracts/` (skill-local choice; not covered by the rule) |

Do **not** write to `docs/security/` from this skill — that directory is the Security agent's. A security/compatibility concern on a trust-boundary surface should route to `security-engineer-subagent`, not be authored here.

Do not persist a trust-boundary Working Draft as the authoritative contract unless the user explicitly asks for a draft file. When saving such a draft, include `working-draft` in the filename or first heading and keep the routing warning in the document. Do not label it `Approved` or present it as implementation-ready until the required agent gate has produced or reviewed it.

**If you're a strategic subagent (BA, TechLead, Security), defer to your own persistence rule, not this table.** Notably, TechLead's *primary* artifact is module READMEs, with `docs/contracts/` only on full runs — a contract-mode ("Stop after Phase 2") dispatch writes module READMEs only. Use your agent's convention for path and filename to avoid double-persisting one artifact under two names, and return per `rules/subagent-return-format.md` (status + evidence path + exit-criteria) — cite the saved path, don't paste the document inline.

Filename when saving standalone: dated kebab-case (`docs/requirements/2026-06-16-kyc-upload-prd.md`); ADRs get a sequence number (`docs/contracts/adr-0007-use-kafka-over-sqs.md`). Add a `Last updated: <date>` footer so staleness checks work.

## Quality bar

A document is done when it's **complete** (every template section present, or marked N/A with a reason), **implementable** (a dev could build without guessing — concrete inputs/outputs/formats/limits), **testable** (QA can derive cases — Given/When/Then, not "works well"), **reviewable** (headings let a stakeholder find their part), and **codegen-ready** (for Specifications, contracts precise enough to generate correct code). A section that can't be filled because the decision genuinely isn't made is an Open Question — not a reason to delete the section.
