# Model Routing for Task Dispatches

When dispatching a `Task` (via the `Task` tool or the Workflow `agent()` call), the dispatcher may override
the target subagent's default model using the `model` parameter. Right-size each dispatch: over-routing to
Opus burns cost; under-routing to Haiku degrades quality on complex tasks.

## Three-Tier Table

| Tier | Model | Use For |
|------|-------|---------|
| **Heavy** | `opus` | Greenfield architecture design, full-mode TechLead, ambiguous multi-domain problems, adversarial verification of complex claims, tasks requiring deep reasoning across many files or an unclear solution space |
| **Standard** | `sonnet` *(default)* | Most implementation tasks: backend/frontend/mobile code, CDK stacks, BDD scenarios, refactors, bug fixes with a clear root cause. When no override is given, subagents inherit the session model (typically sonnet). |
| **Light** | `haiku` | Enumeration-only tasks (list endpoints, count lines, extract field names), simple transformations, stubbing boilerplate, any task where the dispatcher provides a complete template and the agent fills predefined slots |

## Override-Justification Convention

When setting `model` explicitly, justify it in the dispatch label or a comment so reviewers can audit the choice:

```typescript
// Justified downgrade — pure enumeration, no reasoning needed
Task("List all API route paths in src/routes/", { model: "haiku" })

// Justified upgrade — greenfield design with ambiguous trust-boundary scope
Task("Design the multi-tenant auth architecture for PCI-scope services", { model: "opus" })

// No override — standard implementation from a clear contract
Task("Implement POST /orders per docs/contracts/orders.yaml")
// ↑ inherits sonnet (default); override not needed
```

## When NOT to Override

- Do NOT upgrade to `opus` for tasks that already have a contract or spec — the spec resolves the hard
  thinking; sonnet executes against it.
- Do NOT downgrade to `haiku` when the task involves judgment calls, writing tests, or error handling —
  test and error-handling quality degrades noticeably.
- Do NOT upgrade just because a task "feels important" — the default sonnet handles nearly all leaf
  implementation work correctly.

## Per-Agent Defaults

Each agent definition specifies its own `model:` in its frontmatter. A dispatch-level override applies
on top of that default — it changes the model the agent runs on, not its system prompt or capabilities.
When the dispatch model is omitted, the agent's frontmatter `model:` wins; if that is also absent, the
**dispatching agent's own model** is inherited (for a nested dispatcher like the orchestrator, that is
the orchestrator's model, not the root session's).

## Model-Specific Steering

<!-- synced from platform.claude.com/docs prompting-claude-{sonnet-5, opus-4-8, opus-5, fable-5} as of 2026-07-28 -->

The dispatch prompt's *content style* must match the RESOLVED model of the dispatch (override >
frontmatter > dispatching agent's model). The required dispatch fields
(`rules/dispatch-prompt-contract.md`) are model-agnostic coordination needs and always apply; what
flexes is how you write inside them.

**Literal followers — `sonnet` (Sonnet 5) and `opus` (Opus 4.8):**
- They interpret instructions literally and do NOT silently generalize. State scope explicitly:
  "apply this to every endpoint in the file, not just the first" — enumerate the files/sections an
  instruction covers.
- **Reviewer dispatches (preventive rule, not fixing a current defect):** never say "be conservative",
  "only high-severity", or "don't nitpick" — these models follow that literally and silently drop real
  findings. Instead include: *"Report every issue you find, including ones you are uncertain about or
  consider low-severity. Do not filter for importance or confidence — the dispatcher filters
  downstream. Include a confidence level and estimated severity per finding."*
- Effort: default `high`; `xhigh` for the hardest coding/agentic dispatches; they scope strictly to
  what was asked at `low`/`medium`.

**Light — `haiku`:**
- A complete slot-filling template is mandatory: zero open judgment calls, every decision pre-made by
  the dispatcher. If you can't write the dispatch that way, the task isn't haiku-tier.

**Frontier overrides — `fable` (and future Opus 5 class):**
Rarely dispatched-to today; if you DO override a dispatch to a frontier model, invert the style:
- Keep the prompt terse — required fields only; over-prescriptive, enumerated behavioral rules
  *degrade* frontier output.
- Do NOT include "verify your work", "double-check", or self-review instructions — these models
  self-verify unprompted and the instructions cause costly over-verification.
- Do NOT ask it to echo, transcribe, or explain its internal reasoning (triggers refusals on Fable 5).
- Cap its sub-spawning explicitly if the harness allows it to delegate.
