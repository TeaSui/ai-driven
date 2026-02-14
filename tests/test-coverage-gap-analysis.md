# Test Coverage Gap Analysis

> **Note**: This analysis was written when the test suite was first implemented. The codebase has since been fully migrated from JavaScript to Java, and the legacy agent-based workflow has been removed. This document should be reviewed against the current state.

## Current Test State

### Java Unit Tests

All Lambda handlers in the linear workflow have corresponding unit tests:

| Handler | Test File | Coverage |
|---------|-----------|----------|
| `JiraWebhookHandler` | `JiraWebhookHandlerTest.java` | High |
| `FetchTicketHandler` | *(pending — recently renamed)* | — |
| `BitbucketFetchHandler` | `BitbucketFetchHandlerTest.java` | High |
| `ClaudeInvokeHandler` | `ClaudeInvokeHandlerTest.java` | High |
| `PrCreatorHandler` | `PrCreatorHandlerTest.java` | High |
| `MergeWaitHandler` | `MergeWaitHandlerTest.java` | High |

### TypeScript Integration Tests

Located in `tests/`, covering network-level testing:
- Infrastructure validation
- Component integration (handler → handler flow)
- Workflow integration (multi-step chains)
- End-to-end tests (idempotency, rate limiting)

### Known Gaps

1. **FetchTicketHandler** — Renamed from `OrchestratorHandler`; needs a dedicated test
2. **Null parameter validation** — Lambda handlers lack explicit `null` checks on input parameters
3. **Large repository handling** — No test for repos exceeding 2GB ephemeral storage
4. **Claude response edge cases** — Malformed JSON recovery is tested but multi-continuation is not
5. **Step Functions integration** — No automated tests for the actual state machine transitions

## Recommended Next Steps

1. Add `FetchTicketHandlerTest.java` with input validation and error scenarios
2. Add parameterized null-check tests across all handlers
3. Add integration tests for large repos and Claude timeout scenarios
4. Consider CDK assertion tests for infrastructure validation
