---
name: brainstorming
description: Clarify ambiguous requirements and compare meaningful design alternatives before substantial implementation. Use on trust-boundary workflows or when design choices are unresolved; clear routine fixes follow their routing tier.
---

# Design clarification

Read the applicable project context, then identify the outcome, constraints, acceptance evidence and material unknowns. Consult available sources before asking for information they already contain. Ask a concise question only when the answer changes the design; routine decisions can use a stated reasonable assumption.

Compare two or three approaches when the tradeoff matters. Recommend one with its costs, risks and validation. Preserve existing domain conventions and security controls. A configuration change may need a few sentences; a new system needs interfaces, ownership, data flow and failure behavior.

Present the concrete design. Reuse the user's authorization for the same scope; if they delegated implementation/design choices, proceed without repeated section-by-section, spec and execution confirmations. Obtain missing material decisions and separately required permissions before dependent work. This never authorizes destructive actions, external publishing or bypassing native controls beyond the user's scope.

For full-workflow/orchestrator work, record the design/coordination outline in the authorized project location. Obtain Security rules and any needed TechLead contract before the implementation plan; then use writing-plans. Do not invent artifacts or commit/scaffold an existing repository solely because a template says to. The routing rule controls required specialists and quality gates.

Make components understandable and independently testable. Use a design challenger for significant architecture decisions where available; keep review iterations bounded and record unresolved concerns. Use visuals when they help explain an actual visual decision, not as a mandatory intake ceremony.
