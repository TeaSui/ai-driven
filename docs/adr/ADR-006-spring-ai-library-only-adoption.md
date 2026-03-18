# ADR-006: Spring AI Library-Only Adoption

**Status:** SUPERSEDED (migrating to full Spring Boot -- see ADR-007)
**Date:** 2026-03-20
**Authors:** Principal Engineer
**Related:** [impl-23 Spring AI Adoption](../impl/impl-23-spring-ai-adoption.md), ADR-002, ADR-007

## Context and Problem Statement

The custom `ClaudeClient` (~327 lines, raw `java.net.http.HttpClient`) lacked retry logic, prompt caching, and typed API access. A 429 rate-limit response from Claude caused an immediate `RuntimeException`, and repeated system prompts were billed at full token price on every request. The system needed a mature AI client library, but the Lambda deployment model (ADR-002) precluded full Spring Boot adoption due to cold-start impact.

Spring AI 1.1.2 offered prompt caching (Anthropic cache_control), built-in retry with exponential backoff, and typed response records -- but its normal usage assumes Spring Boot auto-configuration.

## Decision Drivers

* Prompt caching could reduce token costs by 50-80% for repeated system prompts
* Retry logic needed for Claude 429/5xx errors (previously caused immediate failure)
* Must not regress Lambda cold start (sub-2-second requirement from ADR-002)
* Typed API access preferred over manual JSON parsing of Anthropic responses
* Agent tool-use loop requires raw content blocks (not abstracted away)
* Team already investing in Spring ecosystem knowledge for future Fargate migration

## Considered Options

* **Spring AI 1.1.2 as library (no Spring Boot)** -- manual instantiation of Spring AI classes
* **Full Spring Boot adoption** -- auto-configuration with Spring AI starter
* **Raw Anthropic Java SDK** -- official Anthropic client library
* **LangChain4j** -- Java LLM framework with Anthropic support

## Decision Outcome

Chosen option: "Spring AI 1.1.2 as library", because it delivers prompt caching (50-80% cost reduction), built-in retry, and typed API access while preserving Lambda cold-start performance by manually instantiating `AnthropicChatModel` and `AnthropicApi` without Spring Boot's DI container.

### Positive Consequences

* Prompt caching via `AnthropicCacheOptions` (SYSTEM_AND_TOOLS strategy) -- 50-80% token cost reduction
* Built-in retry via `RetryTemplate` (3 attempts, 1s-30s exponential backoff)
* Typed response records -- `ChatCompletionResponse`, `ContentBlock`, `Usage`
* Two API paths in one adapter: `AnthropicChatModel` for simple chat, `AnthropicApi` for tool-use
* Lambda cold start unchanged at 1-2 seconds (no Spring Boot overhead)
* Fat JAR impact acceptable: 43MB to 59MB (+16MB after 7 exclusion groups)
* Paves migration path to full Spring Boot on Fargate (same API, just add auto-config)

### Negative Consequences

* Must manually instantiate Spring AI classes (no auto-configuration)
* 7 transitive dependency groups excluded to manage JAR size (maintenance burden)
* Cannot exclude `spring-webflux` and `reactor-core` -- `AnthropicApi` references `WebClient` at class-load
* +16MB JAR size increase (acceptable but non-trivial for Lambda)
* Some Spring AI features unavailable without Spring context (advisors, chat memory auto-wiring)
* **Superseded**: Fargate migration (ADR-007) removes the cold-start constraint that motivated this approach

## Pros and Cons of the Options

### Full Spring Boot Adoption

Spring Boot with spring-ai-anthropic-spring-boot-starter.

* Good, because auto-configuration handles all wiring
* Good, because full Spring AI feature set (advisors, chat memory, MCP client)
* Good, because standard Spring patterns familiar to most Java engineers
* Bad, because 3-8 second cold-start penalty on Lambda (deal-breaker for ADR-002)
* Bad, because +20-30MB additional JAR size from Spring Boot starters
* Bad, because SnapStart only partially mitigates cold-start for Java 21

### Raw Anthropic Java SDK

Official Anthropic client library for Java.

* Good, because minimal dependency footprint
* Good, because direct API mapping -- no abstraction layer
* Bad, because no prompt caching support at time of evaluation
* Bad, because no built-in retry -- must implement ourselves (duplicating ADR-004 work)
* Bad, because manual JSON parsing of responses (same problem as custom ClaudeClient)

### LangChain4j

Java LLM framework with Anthropic integration.

* Good, because supports multiple LLM providers with unified API
* Good, because built-in RAG, memory, and tool support
* Bad, because heavier abstraction -- hides Anthropic-specific features (cache_control)
* Bad, because less mature than Spring AI for Anthropic-specific functionality
* Bad, because different ecosystem from Spring -- team investing in Spring knowledge
* Bad, because tool-use abstraction does not expose raw content blocks needed by AgentOrchestrator
