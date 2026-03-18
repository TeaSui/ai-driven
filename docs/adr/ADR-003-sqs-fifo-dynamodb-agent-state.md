# ADR-003: SQS FIFO + DynamoDB for Agent State

**Status:** ACCEPTED
**Date:** 2026-02-10
**Authors:** Principal Engineer
**Related:** [STRATEGY.md - Agent Mode](../STRATEGY.md), [impl-15 Agent Mode](../impl/impl-15-agent-mode.md), ADR-001

## Context and Problem Statement

Agent mode introduces interactive, multi-turn conversations where users post `@ai` comments on Jira tickets or PRs. Unlike the deterministic pipeline (ADR-001), agent mode requires: (1) exactly-once processing of each comment, (2) sequential processing per ticket to prevent context corruption, (3) persistent conversation state across turns, and (4) backpressure when multiple comments arrive faster than the agent can process them.

Direct Lambda invocation from webhooks risks duplicate processing (webhook retries), race conditions (concurrent comments on the same ticket), and lost state (Lambda crashes mid-conversation).

## Decision Drivers

* Exactly-once processing -- duplicate webhook deliveries must not produce duplicate agent actions
* Per-ticket ordering -- comments on the same ticket must be processed sequentially
* Durable conversation state -- multi-turn context survives Lambda restarts
* Backpressure -- system must gracefully queue bursts of comments
* Cost efficiency -- avoid always-on infrastructure for bursty workloads

## Considered Options

* **SQS FIFO + DynamoDB** -- FIFO queues for ordering/dedup, DynamoDB for state
* **Direct Lambda invocation** -- webhook triggers Lambda directly
* **Step Functions** -- state machine per agent conversation
* **Redis (ElastiCache)** -- in-memory state with pub/sub for ordering

## Decision Outcome

Chosen option: "SQS FIFO + DynamoDB", because FIFO queues provide exactly-once semantics via content-based deduplication and per-ticket ordering via message group IDs, while DynamoDB provides durable, low-latency conversation state that survives Lambda cold starts and crashes.

### Positive Consequences

* Exactly-once semantics via SQS FIFO content-based deduplication (5-minute dedup window)
* Per-ticket ordering via `messageGroupId = ticketKey` -- no concurrent processing of same ticket
* Natural backpressure -- SQS buffers bursts, Lambda processes at controlled concurrency
* DynamoDB conversation state survives Lambda restarts and scaling events
* Cost efficient -- pay per message (SQS) and per read/write (DynamoDB), no idle infrastructure
* DynamoDB TTL auto-expires old conversations (configurable retention)
* Serverless -- scales to zero during off-hours

### Negative Consequences

* SQS FIFO throughput limited to 300 messages/second per queue (sufficient for current scale)
* DynamoDB eventual consistency on reads (mitigated by using strong reads for conversation state)
* Message payload limited to 256KB (conversation context stored in DynamoDB, SQS carries only ticket key + metadata)
* FIFO message group serialization means one slow ticket blocks others in the same group (mitigated by ticketKey grouping -- each ticket independent)
* Additional infrastructure components to monitor and maintain

## Pros and Cons of the Options

### Direct Lambda Invocation

Webhook API Gateway triggers Lambda directly for each comment.

* Good, because simplest architecture -- no intermediate queue
* Good, because lowest latency -- no SQS polling delay
* Bad, because no deduplication -- webhook retries cause duplicate agent actions
* Bad, because no ordering -- concurrent comments on same ticket race for state
* Bad, because no backpressure -- burst of comments can overwhelm Lambda concurrency
* Bad, because lost state on crash -- no durable checkpoint between turns

### Step Functions

One Step Functions execution per agent conversation.

* Good, because built-in state management and visual debugging
* Good, because native wait states for human-in-the-loop approval
* Bad, because Step Functions not designed for long-lived, event-driven conversations
* Bad, because pricing model expensive for multi-turn conversations (per state transition)
* Bad, because new events (comments) cannot be injected into running execution without workarounds

### Redis (ElastiCache)

In-memory state store with pub/sub for ordering.

* Good, because sub-millisecond latency for state reads/writes
* Good, because pub/sub enables real-time event routing
* Bad, because always-on infrastructure cost (~$50-150/month minimum for HA)
* Bad, because state lost on node failure without persistence (Redis AOF adds complexity)
* Bad, because no built-in exactly-once semantics -- must implement deduplication manually
* Bad, because operational overhead of managing ElastiCache cluster
