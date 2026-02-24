# Implementation 21: BedRock Claude Client

## Summary
Added support for AWS BedRock as an alternative Claude provider to avoid Anthropic API rate limits.

## Changes Made

### 1. New Files Created

#### `/application/claude-client/src/main/java/com/aidriven/claude/ClaudeProvider.java`
- Enum defining available Claude providers: `ANTHROPIC_API` and `BEDROCK`
- Includes `fromString()` method for parsing with safe defaults

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
- Added `provider` field (default: "ANTHROPIC_API")
- Added `bedrockRegion` field (default: "us-east-1")
- Added convenience methods `provider()` and `bedrockRegion()` with defaults

#### `/application/core/src/main/java/com/aidriven/core/config/AppConfig.java`
- Added `claudeProvider` field
- Added `bedrockRegion` field
- Updated `getClaudeConfig()` to pass new fields

#### `/application/core/src/main/java/com/aidriven/core/config/ConfigLoader.java`
- Added loading of `CLAUDE_PROVIDER` env var (default: "ANTHROPIC_API")
- Added loading of `BEDROCK_REGION` env var (default: "us-east-1")

#### `/application/lambda-handlers/src/main/java/com/aidriven/lambda/factory/ExternalClientFactory.java`
- Changed `claudeClient()` return type from `ClaudeClient` to `AiClient`
- Added `createAnthropicClient()` method for ANTHROPIC_API provider
- Added `createBedrockClient()` method for BEDROCK provider
- Uses switch expression based on `ClaudeProvider` to instantiate correct client

#### `/application/lambda-handlers/src/main/java/com/aidriven/lambda/factory/ServiceFactory.java`
- Changed `getClaudeClient()` return type from `ClaudeClient` to `AiClient`
- Updated `getProviderRegistry()` to cast to `AiProvider`

#### `/application/lambda-handlers/src/main/java/com/aidriven/lambda/ClaudeInvokeHandler.java`
- Changed `claudeClient` field type from `ClaudeClient` to `AiClient`
- Changed `resolveActiveClient()` return type to `AiClient`
- Changed `activeClient` local variable type to `AiClient`

#### `/application/lambda-handlers/src/main/java/com/aidriven/lambda/AgentProcessorHandler.java`
- Changed `claudeClient` local variable type from `ClaudeClient` to `AiClient`

#### `/application/lambda-handlers/src/test/java/com/aidriven/lambda/AgentEndToEndTest.java`
- Changed `claudeClient` mock type from `ClaudeClient` to `AiClient`

#### `/application/lambda-handlers/src/test/java/com/aidriven/lambda/ClaudeInvokeHandlerTest.java`
- Changed `claudeClient` mock type from `ClaudeClient` to `AiClient`

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `CLAUDE_PROVIDER` | Claude provider to use (`ANTHROPIC_API` or `BEDROCK`) | `ANTHROPIC_API` |
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

1. **Phase 1**: Deploy with `CLAUDE_PROVIDER=ANTHROPIC_API` (current behavior)
2. **Phase 2**: Set up BedRock access in AWS account
3. **Phase 3**: Update Secrets Manager secret with AWS credentials if needed
4. **Phase 4**: Change `CLAUDE_PROVIDER=BEDROCK` and redeploy
5. **Phase 5**: Monitor for any issues and adjust model mappings if needed

## Backward Compatibility

All changes are backward compatible. The default provider is `ANTHROPIC_API`, so existing deployments will continue to work without any configuration changes.
