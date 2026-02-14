# AI-Driven Workflow Automation Tests

Comprehensive end-to-end test suite for the AI-driven code generation workflow.

## Test Structure

```
tests/
├── aws-integration/       # Component integration tests with real AWS services
├── e2e/                   # Full end-to-end workflow tests
├── workflow-integration/  # Component-to-component integration tests
├── utils/                 # Test utilities and helpers
└── test-data-manager/     # Test data fixtures and management

## Documentation

- [Test Cases](test-cases.md) — Comprehensive test case definitions
- [Test Coverage Analysis](test-coverage-gap-analysis.md) — Analysis of missing test coverage
```

## Setup

1. **Install dependencies**:
   ```bash
   npm install
   ```

2. **Configure environment**:
   ```bash
   cp .env.example .env
   # Edit .env with your test credentials
   ```

3. **Verify configuration**:
   ```bash
   npm test -- --testPathPattern=test-config
   ```

## Running Tests

```bash
# Run all tests
npm test

# Run specific test levels
npm run test:integration    # Component integration tests
npm run test:workflow       # Workflow integration tests
npm run test:e2e           # End-to-end tests

# Run with coverage
npm run test:coverage

# Watch mode for development
npm run test:watch

# CI mode
npm run test:ci
```

## Test Levels

### Level 1: Component Integration Tests
Tests individual components with real external services:
- JiraClient with real Jira API
- BitbucketClient with real Bitbucket API
- ClaudeClient with real Claude API (Haiku/Sonnet)
- Bitbucket Client with repository API
- Jira Client with ticket API

### Level 2: Workflow Integration Tests
Tests data flow between components:
- Jira Webhook → Trigger flow
- Fetch Context → Claude Flow
- Claude → PR Creation
- PR Merge → Completion

### Level 3: End-to-End Tests
Tests complete workflows:
- Happy path: Label → PR → Merge → Done
- Error scenarios
- Dry-run mode
- Concurrent execution
- Idempotency

## Test Data Management

Test data is automatically created and cleaned up after each test run. Set `TEST_CLEANUP=false` to preserve test data for debugging.

## Coverage Requirements

- Statements: ≥ 80%
- Branches: ≥ 80%
- Functions: ≥ 80%
- Lines: ≥ 80%

## CI/CD Integration

Tests run automatically on:
- Pull requests (unit + integration tests)
- Main branch commits (all tests including E2E)

See `.github/workflows/test.yml` for CI configuration.
