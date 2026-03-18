# ADR-004: Custom Resilience over Resilience4j

**Status:** ACCEPTED (may revisit with Spring Boot migration -- see ADR-007)
**Date:** 2026-02-15
**Authors:** Principal Engineer
**Related:** [STRATEGY.md - Fail Loudly, Recover Gracefully](../STRATEGY.md), ADR-002, ADR-006

## Context and Problem Statement

The ai-driven system makes external API calls to Claude (Anthropic), Jira, GitHub, and Bitbucket. These calls are subject to rate limiting (429), transient failures (5xx), and network timeouts. The system needs circuit breaker and retry patterns to prevent cascading failures and provide graceful degradation.

The decision was whether to adopt an established resilience library (Resilience4j, Failsafe) or build minimal custom implementations, given the Lambda fat JAR size constraints and the limited subset of patterns actually needed.

## Decision Drivers

* Lambda fat JAR size directly impacts cold-start latency (43MB baseline)
* Only two patterns needed: circuit breaker and retry with exponential backoff
* Full control over behavior required (e.g., custom retry predicates for Claude 429 vs 500)
* Minimal dependency footprint preferred for Lambda deployment
* Must integrate cleanly with ServiceFactory pattern (no Spring context required)

## Considered Options

* **Custom CircuitBreaker + RetryExecutor** -- purpose-built implementations (~1000 LOC)
* **Resilience4j** -- comprehensive resilience library
* **Spring Retry** -- retry template with annotations
* **Failsafe** -- lightweight resilience library by Jonathan Halterman

## Decision Outcome

Chosen option: "Custom CircuitBreaker + RetryExecutor", because the system needs only two resilience patterns (circuit breaker, retry), and custom implementations add ~1000 lines of well-tested code versus 5MB+ of library dependencies -- preserving the Lambda fat JAR budget while providing full control over retry predicates and circuit breaker state transitions.

### Positive Consequences

* Zero additional dependencies -- no JAR size impact
* Full control over retry predicates (distinguish Claude 429 rate-limit from 500 server error)
* CircuitBreaker state transitions tuned to specific API characteristics (Claude vs GitHub vs Jira)
* RetryExecutor supports custom backoff strategies (exponential, jitter, fixed)
* Easy to unit test -- no framework mocking required
* Transparent behavior -- no hidden annotations or aspect weaving

### Negative Consequences

* ~1000 lines of code to maintain (circuit breaker + retry + tests)
* Missing advanced patterns available in Resilience4j (bulkhead, rate limiter, time limiter)
* No community battle-testing -- must thoroughly test edge cases ourselves
* May revisit when migrating to Spring Boot on Fargate where JAR size is less critical
* Risk of subtle concurrency bugs in circuit breaker state machine

## Pros and Cons of the Options

### Resilience4j

Comprehensive resilience library with circuit breaker, retry, bulkhead, rate limiter, and time limiter.

* Good, because battle-tested in production by thousands of organizations
* Good, because rich feature set (bulkhead, rate limiter, time limiter beyond our needs)
* Good, because excellent metrics integration (Micrometer, Prometheus)
* Bad, because adds ~5MB to fat JAR (resilience4j-core + resilience4j-circuitbreaker + resilience4j-retry)
* Bad, because brings transitive dependencies (Vavr, SLF4J bindings)
* Bad, because configuration model designed for Spring/Micronaut DI -- awkward with ServiceFactory

### Spring Retry

Retry template with annotation-based and programmatic APIs.

* Good, because simple programmatic API via RetryTemplate
* Good, because integrates naturally with Spring ecosystem
* Bad, because pulls in spring-retry + spring-aop transitive dependencies
* Bad, because no circuit breaker -- would still need Resilience4j or custom for that
* Bad, because annotation-based retry requires Spring AOP proxy (not available without Spring Boot)

### Failsafe

Lightweight resilience library with fluent API.

* Good, because smaller footprint than Resilience4j (~500KB)
* Good, because clean fluent API without framework dependencies
* Good, because supports retry, circuit breaker, timeout, fallback
* Bad, because still an external dependency to manage and update
* Bad, because smaller community -- fewer production battle-test data points
* Bad, because marginal benefit over custom implementation for only two patterns
