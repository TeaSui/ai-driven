# Architecture Records: RFC and ADR

Both capture technical decisions, at different stages:

- **RFC** — a *proposal* still open for discussion. Lays out the problem, options, and a recommendation; invites comment before commitment. Used heavily for system design, API design, microservices, and AI systems.
- **ADR** — the *record* of a decision already made. Short, immutable once accepted; one decision per file. An RFC often produces one or more ADRs once it lands.

Rule of thumb: if the team is still choosing, write an RFC. If the choice is made and you're recording it for posterity, write an ADR.

In this repo's workflow these are TechLead artifacts and persist to `docs/contracts/`.

---

## RFC — Request For Comments

```
# RFC: <title>

Author: <name>   Status: <Draft | In review | Accepted | Rejected>   Date: <YYYY-MM-DD>

## 1. Context
## 2. Problem
## 3. Goals
## 4. Non-Goals
## 5. Proposed Solution
## 6. Alternatives
## 7. Risks
## 8. Rollout
## 9. Open Questions
```

The header block (author/status/date) mirrors the ADR header so a reader can see at a glance whether the proposal is still open.

`Non-Goals` is load-bearing — it bounds the discussion and prevents scope creep in the comments. `Alternatives` should be honest: list real options with pros *and* cons, not strawmen.

**Worked example (excerpts):**

```
## 1. Context
The KYC service is tightly coupled and hard to scale. We need an
event-driven architecture to decouple origination from decisioning.

## 5. Proposed Solution
Introduce Kafka. New flow:
Origination → Kafka → KYC Workflow → Decision Engine

## 6. Alternatives
Option A — Kafka
  Pros: scalable, ordering per partition, replay
  Cons: more infra to operate
Option B — SQS
  Pros: simpler, managed
  Cons: ordering limitations (FIFO throughput caps)

## 9. Open Questions
- Partition key: customerId vs. applicationId? [NEEDS INPUT]
```

---

## ADR — Architecture Decision Record

Deliberately tiny. One decision, one file, numbered in sequence (`adr-0007-…`). Status moves Proposed → Accepted → (later) Superseded; never edit the decision after Accepted — write a new ADR that supersedes it.

```
# ADR-NNNN: <title>

Status: <Proposed | Accepted | Superseded by ADR-XXXX>
Date: <YYYY-MM-DD>

## Context
## Decision
## Alternatives considered
## Consequences
```

`Alternatives considered` (one line per rejected option, with the reason) is what distinguishes a decision record from an assertion — it shows the decision was a choice, and it answers "why not X?" for the next person who asks.

**Worked example:**

```
# ADR-0007: Use Kafka instead of SQS for KYC events

Status: Accepted
Date: 2026-06-16

## Context
We need ordering guarantees per customer for KYC state transitions, plus
the ability to replay events for the decision engine.

## Decision
Use Kafka with 3 partitions, keyed by customerId.

## Alternatives considered
- SQS FIFO — rejected: per-group throughput cap conflicts with peak volume.
- Postgres outbox + polling — rejected: no native replay, added latency.

## Consequences
Pros: per-key ordering, event replay, horizontal scale.
Cons: higher operational cost than SQS; team must learn Kafka ops.
```

Keep Consequences honest about the downside — an ADR that only lists pros isn't recording a decision, it's marketing.
