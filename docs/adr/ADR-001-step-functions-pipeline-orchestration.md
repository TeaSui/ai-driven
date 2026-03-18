# ADR-001: Step Functions for Pipeline Orchestration

**Status:** ACCEPTED
**Date:** 2026-01-15
**Authors:** Principal Engineer
**Related:** [STRATEGY.md - Pipeline Mode](../STRATEGY.md), ADR-003

## Context and Problem Statement

The ai-driven system needs a reliable orchestration layer for its deterministic code generation pipeline: Jira webhook receipt, ticket/context fetching, Claude code generation, and PR creation. Each step can fail independently (API throttling, Claude timeouts, Git conflicts), and the system must handle retries, timeouts, and partial failures without losing work or creating duplicate PRs.

Early prototypes used direct Lambda-to-Lambda invocation, which proved brittle -- failures in the middle of the chain left orphaned state with no visibility into what succeeded or failed.

## Decision Drivers

* Pipeline steps are sequential and deterministic (Fetch -> Context -> Generate -> PR)
* Each step has different failure modes and retry characteristics
* Operations team needs visibility into pipeline execution state
* Audit trail required for every AI-generated code change
* Timeout handling critical -- Claude generation can take 30-120 seconds

## Considered Options

* **AWS Step Functions** -- managed state machine with built-in retry and visual debugging
* **Direct Lambda chaining** -- each Lambda invokes the next via SDK
* **SQS queues between stages** -- decouple via message queues
* **Custom orchestrator Lambda** -- single Lambda managing the full pipeline

## Decision Outcome

Chosen option: "AWS Step Functions", because it provides built-in retry with exponential backoff, visual execution history for debugging, native timeout handling per step, and a complete audit trail -- all without custom orchestration code.

### Positive Consequences

* Built-in retry with configurable backoff per step (e.g., 3 retries for Claude, 5 for GitHub API)
* Visual Step Functions console shows exact failure point in pipeline
* Execution history provides immutable audit trail for every code generation
* Native timeout handling prevents runaway Lambda costs
* Error handling via Catch/Retry states eliminates custom error routing
* CDK-native definition -- infrastructure as code with type safety

### Negative Consequences

* Step Functions pricing adds ~$0.025 per 1000 state transitions
* State payload limited to 256KB (mitigated by passing S3 references for large context)
* Vendor lock-in to AWS orchestration model
* Cold-start latency of ~200ms for Express Workflows (acceptable for non-interactive pipeline)

## Pros and Cons of the Options

### Direct Lambda Chaining

Each Lambda invokes the next via AWS SDK at the end of its execution.

* Good, because minimal infrastructure overhead
* Good, because simple to understand for small pipelines
* Bad, because no built-in retry -- must implement custom retry logic in each Lambda
* Bad, because no visibility -- failures require CloudWatch log correlation across functions
* Bad, because partial failures leave orphaned state with no recovery mechanism

### SQS Queues Between Stages

Each pipeline stage reads from and writes to SQS queues.

* Good, because natural backpressure and decoupling
* Good, because messages persist on failure (automatic retry via visibility timeout)
* Bad, because adds latency (SQS polling interval) to a synchronous pipeline
* Bad, because no visual execution tracking -- must build custom monitoring
* Bad, because ordering guarantees require FIFO queues with added complexity

### Custom Orchestrator Lambda

A single Lambda function manages the full pipeline, calling each step sequentially.

* Good, because full control over execution flow
* Good, because single deployment unit
* Bad, because Lambda 15-minute timeout constrains total pipeline duration
* Bad, because retry logic, timeout handling, and state management all custom code
* Bad, because single point of failure -- orchestrator crash loses all pipeline state
