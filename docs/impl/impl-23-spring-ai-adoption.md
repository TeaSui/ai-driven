# impl-23: Spring AI Adoption

## Status: ✅ Phase 1 DELIVERED · Phase 2 Groundwork Complete

## Summary

Migrated the AI client layer from a custom `ClaudeClient` (raw `java.net.http.HttpClient`, ~327 lines) to **Spring AI 1.1.2** (`SpringAiClientAdapter`), used as a library without Spring Boot. This delivers prompt caching, built-in retry with exponential backoff, and typed API access while preserving the existing `ServiceFactory` / Lambda cold-start model.

### Why Spring AI?

| Problem (Before) | Solution (After) |
|---|---|
| No retry logic — 429/5xx caused immediate `RuntimeException` | `RetryTemplate` with exponential backoff (3 attempts, 1s–30s) |
| No prompt caching — repeated system prompts billed at full price | `AnthropicCacheOptions` (SYSTEM_AND_TOOLS) + `CacheControl` markers |
| Manual JSON parsing of Anthropic responses | Typed records: `ChatCompletionResponse`, `ContentBlock`, `Usage` |
| No streaming capability | Spring AI's `Flux<ChatResponse>` available (activation pending Fargate) |
| Custom HTTP client maintenance burden | Delegated to Spring AI's well-tested `AnthropicApi` |

### What We Did NOT Adopt

- **Spring Boot** — No auto-configuration, no DI container (Lambda cold-start impact)
- **Spring AI MCP client** — Groundwork done (`SpringAiMcpClientAdapter`), activation pending Fargate
- **Spring AI Chat Memory** — Groundwork done (`DynamoChatMemoryRepository` + `SpringAiChatMemoryAdapter`), activation pending Fargate

---

## Architecture

```
ExternalClientFactory
  └─ createAiClient(model, maxTokens)
       └─ switch (ClaudeProvider) {
            SPRING_AI → SpringAiClientAdapter.fromSecrets(...)  ← default
            BEDROCK   → BedrockClient(...)
          }

SpringAiClientAdapter implements AiClient, AiProvider
  ├─ chat()          → AnthropicChatModel (high-level, retry + cache)
  └─ chatWithTools() → AnthropicApi       (low-level, raw content blocks)
```

### Provider Routing

| Provider | Class | Use Case |
|---|---|---|
| `SPRING_AI` (default) | `SpringAiClientAdapter` | Direct Anthropic API via Spring AI |
| `BEDROCK` | `BedrockClient` | AWS regions without direct Anthropic API access |

The previous `ANTHROPIC_API` provider (custom `ClaudeClient`) has been removed.

---

## Phase 1: Library-Only Adoption (Delivered)

### Changes Made

#### New Files

| File | Description |
|---|---|
| `claude-client/.../SpringAiClientAdapter.java` | Core adapter: `AiClient` + `AiProvider` via Spring AI |
| `claude-client/.../AnthropicResponseParser.java` | Shared JSON parser for Spring AI + Bedrock responses |
| `claude-client/src/test/.../SpringAiClientAdapterTest.java` | 16+ unit tests |
| `claude-client/src/test/.../SpringAiClientAdapterIntegrationTest.java` | 4 integration tests (tagged `integration`) |

#### Modified Files

| File | Change |
|---|---|
| `claude-client/build.gradle` | Added `spring-ai-anthropic:1.1.2` with 7 exclusion groups |
| `gradle.properties` | Added `springAiVersion=1.1.2` |
| `core/.../ClaudeConfig.java` | Default provider changed to `SPRING_AI` |
| `core/.../ConfigLoader.java` | Default `CLAUDE_PROVIDER` changed to `SPRING_AI` |
| `spring-boot-app/.../config/ExternalClientConfig.java` | Routes to `createSpringAiClient()` via Spring Boot configuration |
| `claude-client/.../ClaudeProvider.java` | Removed `ANTHROPIC_API`, defaults to `SPRING_AI` |

#### Deleted Files

| File | Reason |
|---|---|
### SpringAiClientAdapter Design

Two API paths, one adapter:

**`chat()` — Simple text generation**
- Uses `AnthropicChatModel` (Spring AI high-level API)
- Built-in retry via `RetryTemplate`
- Prompt caching via `AnthropicCacheOptions` with `SYSTEM_AND_TOOLS` strategy
- Returns `String`

**`chatWithTools()` — Agent tool-use loop**
- Uses `AnthropicApi.chatCompletionEntity()` (Spring AI low-level API)
- Manual retry via `RetryTemplate`
- Prompt caching via block-style system prompt with `CacheControl("ephemeral")`
- Returns `ToolUseResponse(ArrayNode contentBlocks, String stopReason, int inputTokens, int outputTokens)`
- Maintains raw content block format required by `AgentOrchestrator`'s ReAct loop

### Prompt Caching

```
chat():
  AnthropicCacheOptions.builder()
    .strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)
    .messageTypeMinContentLength(MessageType.SYSTEM, 0)
    .build()

chatWithTools():
  List<ContentBlock> systemBlocks = List.of(
    new ContentBlock(systemPrompt, new CacheControl("ephemeral")));
```

Cache metrics are logged on every response:
```
chatWithTools cache usage: inputTokens=1234, outputTokens=567,
  cacheCreationInputTokens=800, cacheReadInputTokens=400
```

### Dependency Management

Added `spring-ai-anthropic:1.1.2` with exclusions to minimize fat JAR impact:

| Excluded | Reason |
|---|---|
| `io.projectreactor.netty:reactor-netty-http` | Not needed (blocking API only) |
| `io.netty` | Not needed (blocking API only) |
| `org.springframework:spring-context` | No DI container |
| `org.springframework:spring-context-support` | No DI container |
| `org.springframework:spring-aop` | No AOP |
| `org.springframework:spring-expression` | No SpEL |
| `org.springframework:spring-messaging` | No messaging |
| `org.springframework.ai:spring-ai-template-st` | No prompt templating |

**Cannot exclude**: `spring-webflux` and `reactor-core` — `AnthropicApi` references `WebClient` at class-load time.

**Fat JAR impact**: 43MB → 59MB (+16MB)

---

## Phase 2: Groundwork (Complete, Activation Pending Fargate)

### MCP Adapter

| File | Description |
|---|---|
| `mcp-bridge/.../SpringAiMcpClientAdapter.java` | `ToolCallbackProvider` wrapping `McpBridgeToolProvider` |
| `mcp-bridge/.../SpringAiMcpToolCallback.java` | `ToolCallback` for individual MCP tools |

- 39 tests in mcp-bridge module
- Additive — no existing MCP code modified

### Chat Memory Adapter

| File | Description |
|---|---|
| `core/.../DynamoChatMemoryRepository.java` | `ChatMemoryRepository` backed by existing `ConversationRepository` |
| `core/.../SpringAiChatMemoryAdapter.java` | `MessageWindowChatMemory` wrapper with `appendAndBuild` contract |

- 49 tests in core module
- Same API contract as `ConversationWindowManager`
- Additive — no existing conversation code modified

### Migration Path (Phase 2 → Full Activation)

1. **Coexist** (current): Spring AI adapters exist alongside legacy implementations
2. **Migrate**: When Fargate lands, wire adapters into agent runtime, verify parity
3. **Remove**: Delete legacy `ConversationWindowManager` and raw MCP bridge after migration

---

## Configuration

### Environment Variables

| Variable | Description | Default |
|---|---|---|
| `CLAUDE_PROVIDER` | `SPRING_AI` or `BEDROCK` | `SPRING_AI` |
| `CLAUDE_MODEL` | Default model ID | `claude-sonnet-4-6` |
| `CLAUDE_MAX_TOKENS` | Max output tokens | `32768` |
| `CLAUDE_TEMPERATURE` | Temperature (0.0–1.0) | `0.2` |
| `BEDROCK_REGION` | AWS region for Bedrock | `us-east-1` |

---

## Testing

### Unit Tests

```bash
./gradlew :claude-client:test    # 16+ SpringAiClientAdapter tests
./gradlew :core:test             # 49 chat memory adapter tests
./gradlew :mcp-bridge:test       # 39 MCP adapter tests
```

### Integration Tests

```bash
ANTHROPIC_API_KEY=sk-... ./gradlew :claude-client:integrationTest
```

4 tests (tagged `integration`, excluded from default `./gradlew test`):
- Simple chat round-trip
- Tool use trigger (stop_reason = tool_use)
- Tool result round-trip
- Token usage reporting

---

## Success Metrics

| Metric | Before | After | Status |
|---|---|---|---|
| Prompt caching | None | SYSTEM_AND_TOOLS strategy | Enabled |
| Retry on 429/5xx | None (immediate failure) | 3 attempts, exponential backoff | Enabled |
| Fat JAR size | 43MB | 59MB (+16MB) | Acceptable |
| Lambda cold start | ~1-2s | ~1-2s (no Spring Boot) | No regression |
| Token cost (repeated prompts) | Full price | 50-80% reduction via cache | Monitoring |

---

## Dependencies

- impl-07 (Multi-Model Support) — `withModel()` builder pattern
- impl-15 (Agent Mode) — `chatWithTools()` contract with `AgentOrchestrator`
- impl-21 (Bedrock Client) — `ClaudeProvider` routing, shared `AnthropicResponseParser`

---

## Decision Log

| Date | Decision | Rationale |
|---|---|---|
| 2026-03-20 | Library-only, no Spring Boot | Lambda cold start would regress 3-5x with Spring Boot DI |
| 2026-03-20 | Two API paths in one adapter | `AnthropicChatModel` for simple chat, `AnthropicApi` for tool-use (AgentOrchestrator needs raw content blocks) |
| 2026-03-20 | Exclude 7 transitive dep groups | Minimize JAR bloat; keep spring-webflux/reactor (required at class-load time) |
| 2026-03-20 | Remove ANTHROPIC_API provider | Redundant — Spring AI wraps the same Anthropic API with better retry + caching |
| 2026-03-20 | Phase 2 as additive adapters | Zero risk to existing code; 3-phase migration when Fargate arrives |
