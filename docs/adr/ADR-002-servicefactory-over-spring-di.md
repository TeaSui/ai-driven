# ADR-002: ServiceFactory over Spring DI

**Status:** SUPERSEDED (migrating to Spring Boot on Fargate -- see ADR-007)
**Date:** 2026-01-20
**Authors:** Principal Engineer
**Related:** [impl-23 Spring AI Adoption](../impl/impl-23-spring-ai-adoption.md), ADR-006, ADR-007

## Context and Problem Statement

The ai-driven system runs on AWS Lambda, where cold-start latency directly impacts user experience. A Jira webhook triggering code generation must respond within seconds. Spring Boot's component scanning, auto-configuration, and dependency injection container adds 3-8 seconds to cold start on Java 21, which is unacceptable for a webhook-driven system.

The system needs dependency management for 20+ services (AI clients, source control clients, issue tracker clients, DynamoDB repositories, tool providers) without framework overhead.

## Decision Drivers

* Lambda cold start must remain under 2 seconds
* Fat JAR size impacts cold start (currently 43MB, Spring Boot would add 20-30MB)
* Lazy initialization critical -- not all services needed for every Lambda invocation
* Team familiarity with Spring patterns but willingness to trade convenience for performance
* No need for runtime bean resolution or AOP proxies

## Considered Options

* **Custom ServiceFactory with ConcurrentHashMap.computeIfAbsent()** -- lazy singleton pattern
* **Spring Boot** -- full DI container with auto-configuration
* **Google Guice** -- lightweight DI with compile-time module binding
* **Dagger 2** -- compile-time DI with zero runtime overhead
* **Manual constructor injection** -- no container, wire everything in main()

## Decision Outcome

Chosen option: "Custom ServiceFactory with ConcurrentHashMap.computeIfAbsent()", because it provides lazy initialization with thread-safe singleton semantics, zero framework overhead, and sub-2-second cold starts -- trading DI convenience for Lambda performance.

### Positive Consequences

* Cold start remains at 1-2 seconds (vs 4-10 seconds with Spring Boot)
* Lazy initialization -- services created only when first accessed
* Thread-safe via ConcurrentHashMap.computeIfAbsent() -- no double-initialization
* Zero additional dependencies -- uses only JDK collections
* Explicit wiring makes dependency graph visible and debuggable
* Fat JAR stays at 43MB (no Spring Boot overhead)

### Negative Consequences

* ServiceFactory becomes a "god object" (~400 LOC) -- violates SRP
* No compile-time dependency validation -- wiring errors surface at runtime
* Manual lifecycle management -- no @PreDestroy or graceful shutdown hooks
* Harder to test in isolation -- must mock ServiceFactory or extract sub-factories
* New engineers unfamiliar with pattern (mitigated by documentation)
* **Superseded**: migrating to Spring Boot on Fargate where cold start is not a concern

## Pros and Cons of the Options

### Spring Boot

Full dependency injection with auto-configuration and component scanning.

* Good, because industry-standard DI with extensive ecosystem
* Good, because compile-time validation via @Autowired and constructor injection
* Good, because lifecycle management (@PostConstruct, @PreDestroy, health checks)
* Bad, because 3-8 second cold-start penalty on Lambda (component scanning + auto-config)
* Bad, because 20-30MB additional JAR size from Spring Boot starters
* Bad, because SnapStart mitigates but does not eliminate the overhead

### Google Guice

Lightweight DI framework with runtime module binding.

* Good, because lighter than Spring Boot (~1MB)
* Good, because explicit module binding (no classpath scanning)
* Bad, because still has runtime reflection overhead (~500ms cold-start addition)
* Bad, because smaller ecosystem and fewer engineers familiar with it
* Bad, because module binding still more complex than direct factory methods

### Dagger 2

Compile-time dependency injection via annotation processing.

* Good, because zero runtime overhead -- all wiring resolved at compile time
* Good, because compile-time validation of dependency graph
* Bad, because annotation processor adds build complexity
* Bad, because generated code harder to debug
* Bad, because less flexible for runtime configuration (environment-based provider selection)

### Manual Constructor Injection

Wire all dependencies explicitly in a main() or handler init method.

* Good, because simplest possible approach -- no framework, no pattern
* Good, because fully explicit and debuggable
* Bad, because no lazy initialization -- all services created at startup
* Bad, because wiring code grows linearly with service count (unmaintainable at 20+ services)
* Bad, because no thread safety guarantees without additional synchronization
