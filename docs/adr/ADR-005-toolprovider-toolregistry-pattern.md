# ADR-005: ToolProvider and ToolRegistry Pattern

**Status:** ACCEPTED
**Date:** 2026-02-20
**Authors:** Principal Engineer
**Related:** [STRATEGY.md - Conversational Agent](../STRATEGY.md), [impl-18 AST Context](../impl/impl-18-ast-context.md), ADR-003

## Context and Problem Statement

The agent mode ReAct loop requires an extensible tool system where Claude can invoke actions (read files, search code, create PRs, query Jira). Initial implementation used hardcoded switch statements mapping tool names to handler functions. As the tool count grew from 5 to 20+, the switch statement became unmaintainable, and new requirements emerged: per-ticket tool filtering (restrict tools based on ticket type), risk-based access control (separate read-only tools from write tools), and output truncation (large tool outputs must be truncated to stay within context window).

## Decision Drivers

* Tool count growing rapidly (5 at launch, 20+ by Q1 2026, projected 40+ with MCP)
* Need per-ticket filtering -- restrict available tools based on ticket labels or type
* Risk-based access control -- read tools available by default, write tools require explicit approval
* Output truncation -- tool outputs (e.g., file contents) must be truncated per provider limits
* MCP (Model Context Protocol) integration requires dynamic tool registration at runtime
* Must work without Spring DI -- tools registered via ServiceFactory

## Considered Options

* **Namespace-based ToolProvider/ToolRegistry** -- providers register tools with namespace prefixes
* **Hardcoded switch statements** -- map tool names to handler functions in AgentOrchestrator
* **Spring AI @Tool annotations** -- annotate methods as tools, discovered via Spring context
* **MCP-only** -- all tools exposed via MCP protocol, no local registry

## Decision Outcome

Chosen option: "Namespace-based ToolProvider/ToolRegistry", because it provides extensibility through provider registration, namespace isolation to prevent tool name collisions, longest-prefix routing for flexible tool resolution, and a GuardedToolRegistry layer for risk-based access control -- all without framework dependencies.

### Positive Consequences

* Namespace isolation -- `github.create_pr` and `jira.create_issue` cannot collide
* Longest-prefix routing -- `github.` prefix routes all GitHub tools to GitHubToolProvider
* GuardedToolRegistry wraps ToolRegistry with risk-level checks (READ, WRITE, ADMIN)
* Output truncation configurable per provider (e.g., file content capped at 50KB, search results at 20KB)
* New tools added by implementing ToolProvider interface and registering with ToolRegistry
* MCP tools integrate via McpBridgeToolProvider -- same ToolProvider interface
* Per-ticket filtering via ToolRegistry.getToolsForContext(ticketContext) method
* Tool definitions (name, description, input_schema) auto-generated for Claude tool-use API

### Negative Consequences

* Namespace convention must be enforced by code review (no compile-time enforcement)
* Longest-prefix routing can be surprising if namespaces overlap (mitigated by convention)
* GuardedToolRegistry adds a layer of indirection for tool invocation
* Tool discovery requires scanning registered providers (acceptable at 40 tools, may need indexing at 100+)

## Pros and Cons of the Options

### Hardcoded Switch Statements

Single switch/case block in AgentOrchestrator mapping tool names to handlers.

* Good, because simple and explicit -- easy to trace which code handles which tool
* Good, because no abstraction overhead
* Bad, because every new tool requires modifying AgentOrchestrator (violates Open/Closed Principle)
* Bad, because no namespace isolation -- tool name collisions possible
* Bad, because no risk-level separation -- all tools equally accessible
* Bad, because output truncation must be applied per-case (duplicated logic)

### Spring AI @Tool Annotations

Annotate Java methods with @Tool, discovered via Spring component scanning.

* Good, because minimal boilerplate -- annotate and done
* Good, because integrates with Spring AI ChatClient natively
* Bad, because requires Spring context for component scanning (conflicts with ADR-002 ServiceFactory)
* Bad, because no namespace isolation -- tool names are flat strings
* Bad, because risk-level control requires additional annotation layer
* Bad, because not available until Spring Boot migration (ADR-007)

### MCP-Only

All tools exposed exclusively via Model Context Protocol servers.

* Good, because language-agnostic -- tools can be implemented in any language
* Good, because standard protocol with growing ecosystem
* Bad, because adds network hop latency for every tool call (~50-100ms per call)
* Bad, because MCP server lifecycle management adds operational complexity
* Bad, because local tools (AST parsing, file reading) do not benefit from protocol overhead
* Bad, because MCP spec still evolving -- breaking changes possible
