# Product Vision — AI-Driven Development Platform

## One line

**A trusted AI engineering teammate that owns the boring half of the SDLC — ticket triage, implementation, review, incident response — while humans stay in control of every decision that matters.**

## Who this is for

- **Engineering orgs at 30–300 engineers** that ship daily, have a working Jira/GitHub/Slack/DataDog toolchain, and feel the pain of the "80% of tickets that are routine but still expensive."
- Early-adopter segment: fintech and marketplace teams where correctness matters, auditability is non-negotiable, and time-to-production is a competitive moat.

## Problem we solve

1. Senior engineers spend 30–50% of their time on well-scoped but tedious work (small bug fixes, config changes, predictable feature tickets, review comments, oncall triage).
2. Existing "AI copilots" help *inside* the editor; they don't close the loop back to Jira, the PR, the incident ticket, or the team channel.
3. Teams that try to automate with shell scripts end up with brittle glue and no accountability trail.

## What makes us different

1. **End-to-end, not assistive.** Jira → implementation → PR → review → merge → incident correlation. One platform, one audit trail, one cost line.
2. **Observable by default.** Every Claude call, every tool, every token, every dollar is streamed in real time to the UI and the event log. No black boxes.
3. **Human-in-the-loop is a first-class state**, not an afterthought. Auto-merge is OFF by default; every stage has a clear "who did this — AI or human?" answer.
4. **Bring-your-own-model posture.** Anthropic Direct, AWS Bedrock, and a mock provider; circuit breaker switches providers on outage.
5. **RAG grounded in the customer's code**, not a shared index. Embedding index is per-repo, per-team.
6. **Cost ceilings are hard**, not hopeful. A $100/day cap means the dispatch button disables — it does not over-run with a warning.
7. **Evaluation is continuous**, not a one-off benchmark. Every completed task is auto-graded (Haiku) across task-specific dimensions.

## North-Star metrics

| Metric | Year-1 target | Why it matters |
|---|---|---|
| Engineering hours saved / team / week | 80 h | Primary value prop |
| PR merge success rate (AI-authored) | ≥ 85% | Trust |
| Mean time to detect (MTTD) on paged incidents | < 90 s | Incident ROI |
| Cost per merged PR | < $2.50 | Unit economics |
| Evaluation score (weighted avg) | ≥ 7.5 / 10 | Quality floor |
| Customer NPS | ≥ 50 | Retention signal |

## Design principles

1. **Observability over magic.** Streaming thinking view, full event log, DataDog traces. Ship the "why" alongside the "what."
2. **Defaults bias to safety.** Auto-merge OFF. Write-tools scoped per-repo. Secrets masked at ingress.
3. **Reversible by default.** Every AI action has an undo (revert PR, re-open Jira, rollback merge). Emergency stop drains queues instantly.
4. **Multi-tenant from day one.** Team-scoped everything. No implicit cross-tenant reads, ever.
5. **Docs are load-bearing.** The platform coordinates many systems; a spec drift *is* a bug.
6. **Cost is a product feature.** Prompt caching, model routing (Haiku/Sonnet/Opus), RAG context budgets — all visible, all tunable.
7. **Offline gracefully.** If Anthropic/Bedrock is down, tasks queue with a clear status rather than fail silently. Circuit breaker + mock provider for demos.

## 3-year thesis

- Year 1 — **Trusted assistant** for routine tickets, reviews, and oncall triage in ~20 pilot customers.
- Year 2 — **Team multiplier** via workflow composition (define a bespoke pipeline per repo), cross-repo refactors, proactive dependency updates, security patch bot.
- Year 3 — **Engineering OS** — platform on which customers build their own agents (public SDK), mature eval suite, opt-in fine-tuned models per team.

## What we are explicitly **not** doing

- Not replacing engineers. The model makes suggestions; merges still require a person unless a team explicitly opts in per repo.
- Not a general-purpose IDE copilot. We live outside the editor.
- Not building our own frontier model. We use Anthropic; we add the wiring, observability, and product surfaces.
- Not targeting solo devs or <10-person teams in year 1 — our value depends on repeatable ticket volume.

## Open questions (to revisit)

1. Should the RAG index be per-repo or per-monorepo? Trade-off: cost (per-repo smaller) vs. cross-repo search quality (monorepo better).
2. Do we expose prompt templates to customers, or keep them proprietary? Leaning "yes, expose" for trust.
3. Pricing unit: per-seat, per-merged-PR, per-token? Current hypothesis: seat + metered overage on tokens above fair-use.
4. How do we onboard a customer whose repo is behind a corporate VPC — self-hosted control plane option in year 2?

## Anti-goals

- Do not optimize for "AI that writes the most code". Optimize for "AI that merges the most *correct* code with the fewest reviewer round-trips."
- Do not ship features whose failure mode is invisible to the operator.
- Do not accept "the model just did it" as a debugging answer — every action must be traceable to a tool call and a prompt.
