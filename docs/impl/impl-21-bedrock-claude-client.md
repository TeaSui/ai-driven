# Implementation 21: Claude Client Providers (Bedrock + Spring AI)

## Summary
Added support for multiple Claude providers. Originally introduced AWS Bedrock as an alternative to direct Anthropic API. Subsequently migrated to **Spring AI** (`SpringAiClientAdapter`) as the default provider, replacing the custom `ClaudeClient`. The `ANTHROPIC_API` provider has been removed.

## Changes Made

### 1. New Files Created

#### `/application/claude-client/src/main/java/com/aidriven/claude/ClaudeProvider.java`
- Enum defining available Claude providers: `SPRING_AI` (default) and `BEDROCK`
- Includes `fromString()` method for parsing, defaults to `SPRING_AI`

#### `/application/claude-client/src/main/java/com/aidriven/claude/SpringAiClientAdapter.java`
- Implements `AiClient` and `AiProvider` interfaces using Spring AI 1.1.2
- Uses `AnthropicChatModel` for simple chat with built-in retry and prompt caching
- Uses low-level `AnthropicApi` for tool-use calls maintaining raw content block format
- Prompt caching via `AnthropicCacheOptions` (SYSTEM_AND_TOOLS) and `CacheControl` markers
- `RetryTemplate` with exponential backoff (3 attempts, 1s-30s)

#### `/application/claude-client/src/main/java/com/aidriven/claude/BedrockClient.java`
- Implements `AiClient` and `AiProvider` interfaces
- Uses AWS SDK v2 BedRock Runtime client
- Supports model mapping from Anthropic names to BedRock model IDs
- Implements tool use (function calling)
- Includes `withModel()`, `withMaxTokens()`, `withTemperature()` builder methods

#### `/application/claude-client/src/test/java/com/aidriven/claude/BedrockClientTest.java`
- Unit tests for BedrockClient
- Tests model mapping, builder methods, and ToolUseResponse record

#### `/application/claude-client/src/test/java/com/aidriven/claude/ClaudeProviderTest.java`
- Unit tests for ClaudeProvider enum

### 2. Modified Files

#### `/application/claude-client/build.gradle`
- Added AWS SDK BOM dependency (`software.amazon.awssdk:bom:${awsSdkVersion}`)
- Added BedRock Runtime dependency (`software.amazon.awssdk:bedrockruntime`)
- Added test dependencies (JUnit, Mockito, AssertJ)

#### `/application/core/src/main/java/com/aidriven/core/agent/AiClient.java`
- Added `chat(String systemPrompt, String userMessage)` method
- Added `getModel()` method
- Added `withModel(String model)` method

#### `/application/core/src/main/java/com/aidriven/core/config/ClaudeConfig.java`
- Added `provider` field (default: "SPRING_AI")
- Added `bedrockRegion` field (default: "us-east-1")
- Added convenience methods `provider()` and `bedrockRegion()` with defaults

#### `/application/core/src/main/java/com/aidriven/core/config/AppConfig.java`
- Added `claudeProvider` field
- Added `bedrockRegion` field
- Updated `getClaudeConfig()` to pass new fields

#### `/application/core/src/main/java/com/aidriven/core/config/ConfigLoader.java`
- Added loading of `CLAUDE_PROVIDER` env var (default: "SPRING_AI")
- Added loading of `BEDROCK_REGION` env var (default: "us-east-1")

#### `/application/spring-boot-app/src/main/java/com/aidriven/app/config/ExternalClientConfig.java`
- Routes to `createBedrockClient()` or `createSpringAiClient()` based on `CLAUDE_PROVIDER` env var
- `createSpringAiClient()` calls `SpringAiClientAdapter.fromSecrets()`
- Provider selection now happens at Spring Boot startup time

#### `/application/spring-boot-app/src/main/java/com/aidriven/app/config/AgentConfig.java`
- Registers the selected `AiClient` bean for injection into agent services
- Provides `AiProvider` cast to the provider registry

#### `/application/spring-boot-app/src/main/java/com/aidriven/app/PipelineService.java`
- Uses injected `AiClient` for code generation via Spring Boot constructor injection
- Supports both `SPRING_AI` and `BEDROCK` providers transparently

#### `/application/spring-boot-app/src/test/.../AiClientIntegrationTest.java`
- Integration tests validating both provider implementations

#### Tests
- `/application/spring-boot-app/src/test/.../PipelineServiceTest.java` — Unit tests validating both provider implementations

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `CLAUDE_PROVIDER` | Claude provider to use (`SPRING_AI` or `BEDROCK`) | `SPRING_AI` |
| `BEDROCK_REGION` | AWS region for BedRock (when using BEDROCK provider) | `us-east-1` |

### Model Mapping

| Anthropic Model | BedRock Model ID |
|-----------------|------------------|
| `claude-opus-4-6` | `anthropic.claude-3-5-sonnet-20240620-v1:0` |
| `claude-3-5-sonnet-20240620` | `anthropic.claude-3-5-sonnet-20240620-v1:0` |
| `claude-3-opus-20240229` | `anthropic.claude-3-opus-20240229-v1:0` |
| `claude-3-sonnet-20240229` | `anthropic.claude-3-sonnet-20240229-v1:0` |
| `claude-3-haiku-20240307` | `anthropic.claude-3-haiku-20240307-v1:0` |

## Usage

To switch from Anthropic API to BedRock, set the environment variable:

```bash
export CLAUDE_PROVIDER=BEDROCK
export BEDROCK_REGION=us-east-1  # Optional, defaults to us-east-1
```

The `CLAUDE_SECRET_ARN` should contain AWS credentials if not using the default AWS credential chain. The secret JSON format:

```json
{
  "awsAccessKey": "YOUR_ACCESS_KEY",
  "awsSecretKey": "YOUR_SECRET_KEY"
}
```

If `awsAccessKey` and `awsSecretKey` are not present in the secret, BedRock client will use the default AWS credential chain (IAM roles, environment variables, etc.).

## Testing

Unit tests have been added for:
- `ClaudeProvider` enum parsing
- `BedrockClient` model mapping and builder methods
- `ToolUseResponse` record functionality

Run tests with:
```bash
./gradlew :claude-client:test
```

## Migration Path

1. **Phase 1** (Delivered): `SPRING_AI` is the default provider — uses `SpringAiClientAdapter` with prompt caching and retry
2. **Phase 2**: Set up Bedrock access if needed for regions without direct Anthropic API access
3. **Phase 3**: Change `CLAUDE_PROVIDER=BEDROCK` and redeploy

> **Note:** The original `ANTHROPIC_API` provider (custom `ClaudeClient`) has been removed. All deployments now use `SPRING_AI` by default.

## Backward Compatibility

The default provider changed from `ANTHROPIC_API` to `SPRING_AI` as of the Spring AI migration. The custom `ClaudeClient` has been removed. Existing deployments must update to use `SPRING_AI` (default) or `BEDROCK`.
