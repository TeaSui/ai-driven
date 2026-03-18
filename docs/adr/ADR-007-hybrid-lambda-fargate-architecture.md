# ADR-007: Hybrid Lambda + Fargate Architecture (Planned)

**Status:** PROPOSED
**Date:** 2026-03-21
**Authors:** Principal Engineer
**Related:** [STRATEGY.md - Runtime & Infra](../STRATEGY.md), ADR-002, ADR-006

## Context and Problem Statement

The ai-driven system currently runs entirely on AWS Lambda. While Lambda works well for the deterministic pipeline (ADR-001) and short agent interactions, agent mode increasingly requires capabilities that conflict with Lambda's constraints: conversations can exceed 15 minutes (Lambda maximum timeout), streaming responses to users requires persistent connections (WebSocket/SSE), and the Spring AI library-only approach (ADR-006) prevents using the full Spring ecosystem (advisors, chat memory auto-wiring, MCP client).

The multi-agent swarm (Orchestrator, Coder, Reviewer, Tester) amplifies these issues -- a complex ticket may require 30+ minutes of coordinated agent work with streaming progress updates.

## Decision Drivers

* Agent conversations can exceed Lambda's 15-minute timeout
* Streaming responses (WebSocket/SSE) require persistent connections not supported by Lambda
* Full Spring Boot enables Spring AI advisors, auto-wired chat memory, MCP client
* Cold-start concern (ADR-002) eliminated by Fargate's always-warm container model
* Pipeline mode (short, deterministic) benefits from Lambda's pay-per-invocation pricing
* Multi-agent swarm requires long-running coordination processes
* Must not disrupt production pipeline during migration

## Considered Options

* **Hybrid Lambda + Fargate** -- Lambda for pipeline, Fargate for agent
* **Lambda SnapStart** -- reduce cold start while staying on Lambda
* **Lambda with increased timeout** -- extend Lambda timeout to maximum (15 min)
* **Pure Fargate** -- migrate everything to Fargate

## Decision Outcome

Chosen option: "Hybrid Lambda + Fargate", because it preserves Lambda's cost efficiency for the short-lived deterministic pipeline while moving agent mode to Fargate where long-running processes, streaming, and full Spring Boot are supported -- with no disruption to the production pipeline during migration.

### Positive Consequences

* Agent mode unrestricted by Lambda timeout -- conversations can run 30+ minutes
* Full Spring Boot on Fargate enables Spring AI advisors, chat memory, MCP client auto-wiring
* WebSocket/SSE streaming for real-time agent progress updates
* Cold-start eliminated for agent mode -- Fargate containers stay warm
* Pipeline mode retains Lambda cost efficiency (pay-per-invocation, scale-to-zero)
* Incremental migration -- pipeline and agent can be migrated independently
* Spring AI library-only workarounds (ADR-006) can be removed on Fargate
* Fargate supports health checks, graceful shutdown, and rolling deployments

### Negative Consequences

* Fargate has minimum cost (~$15-30/month for always-on container)
* Two deployment models to maintain (Lambda CDK + Fargate CDK/ECS)
* Networking complexity -- Fargate needs VPC, security groups, load balancer
* Agent mode loses Lambda's automatic scale-to-zero (mitigated by Fargate auto-scaling with minimum 0 tasks)
* Spring Boot migration requires re-wiring ServiceFactory to Spring DI (one-time effort)
* Must maintain API compatibility during transition period (both Lambda and Fargate serving agent requests)

## Pros and Cons of the Options

### Lambda SnapStart

Use Lambda SnapStart to reduce Java cold-start via CRaC checkpoint/restore.

* Good, because no infrastructure change -- stays on Lambda
* Good, because cold start reduced from 2s to ~200ms
* Bad, because does not solve 15-minute timeout constraint
* Bad, because does not enable streaming (WebSocket/SSE)
* Bad, because SnapStart has compatibility issues with some libraries (serialization constraints)
* Bad, because still cannot run full Spring Boot effectively (checkpoint size limits)

### Lambda with Increased Timeout

Set Lambda timeout to maximum 15 minutes, use Step Functions for longer workflows.

* Good, because minimal infrastructure change
* Good, because Step Functions can coordinate multiple 15-minute Lambdas
* Bad, because 15 minutes still insufficient for complex multi-agent swarm tasks
* Bad, because no streaming support
* Bad, because paying for idle time during Claude API waits (Lambda billed per-ms)
* Bad, because Step Functions add per-transition cost for multi-turn conversations

### Pure Fargate

Migrate everything (pipeline + agent) to ECS Fargate.

* Good, because single deployment model -- simpler operations
* Good, because full Spring Boot for all components
* Bad, because pipeline mode loses Lambda's pay-per-invocation efficiency
* Bad, because always-on cost even during off-hours when no tickets are processed
* Bad, because over-provisioned for pipeline (short-lived, infrequent, deterministic)
* Bad, because larger blast radius -- infrastructure change affects both modes simultaneously
