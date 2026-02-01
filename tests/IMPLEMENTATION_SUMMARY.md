# Test Implementation Summary

## Quick Start

```bash
# Java unit tests
cd application && ./gradlew test

# TypeScript integration tests
cd tests && npm install && npm test

# Run specific suites
cd tests && npm run test:unit
cd tests && npm run test:integration
cd tests && npm run test:e2e
cd tests && npm run test:workflow
```

## Architecture

### Java Unit Tests (JUnit 5 + Mockito)

Located in each module's `src/test/java/` directory. Uses constructor injection with mocked dependencies where possible:

| Module | Key Test Files |
|--------|---------------|
| `core` | `TicketInfoTest`, `ProcessingStatusTest`, `IdempotencyServiceTest`, `TicketStateRepositoryTest` |
| `claude-client` | `PromptBuilderTest` |
| `bitbucket-client` | *(via integration tests)* |
| `jira-client` | *(via integration tests)* |
| `lambda-handlers` | `JiraWebhookHandlerTest`, `BitbucketFetchHandlerTest`, `ClaudeInvokeHandlerTest`, `PrCreatorHandlerTest`, `MergeWaitHandlerTest`, `DryRunModeTest` |

### TypeScript Integration Tests (Jest + nock)

Located in `tests/` directory with dedicated subdirectories:

```
tests/
  unit/                           # API client unit tests
  integration/                    # Component-level tests with mocked HTTP
  workflow-integration/           # Multi-handler flow tests
  e2e/                           # End-to-end scenario tests
  utils/                         # Test setup, helpers, factories
```

**Key Configuration:**
- Test timeout: 30s (for integration tests)
- Coverage threshold: 80% (branches, functions, lines, statements)
- HTTP mocking: `nock` for all external API calls

### AI Engine

Tests validate prompts and responses against the **Claude AI direct API** (not AWS Bedrock). The system uses model `claude-sonnet-4-20250514` with direct HTTP calls via `ClaudeClient`.

## CI/CD

GitHub Actions workflow (`.github/workflows/test.yml`) runs:
1. Java unit tests (`./gradlew test`)
2. TypeScript integration tests (`npm run test:ci`)
3. Coverage reporting (Gradle JaCoCo + Jest LCOV)

### Required Secrets

| Secret | Usage |
|--------|-------|
| `AWS_ACCESS_KEY_ID` | AWS SDK authentication (CI only) |
| `AWS_SECRET_ACCESS_KEY` | AWS SDK authentication (CI only) |
| `CLAUDE_API_KEY` | Claude API key (for E2E tests, if enabled) |

> **Note**: For production, all credentials are stored in AWS Secrets Manager. CI/CD uses GitHub Actions secrets.

## Coverage Goals

- **Java**: All handlers tested with unit tests, targeting 80%+ line coverage
- **TypeScript**: Integration tests validate cross-handler data flow and API contracts
- **Missing**: See [Test Coverage Gap Analysis](../docs/test-coverage-gap-analysis.md) for open items
