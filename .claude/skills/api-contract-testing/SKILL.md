---
name: api-contract-testing
description: |
  API contract testing, OpenAPI validation, and structured test analysis for
  microservices. Consumer-driven contracts (Pact for Spring Boot, Go), OpenAPI
  spec compliance, baseline regression comparison, and report generation.
  Use when: validating API contracts between services, checking OpenAPI spec
  compliance, comparing test results against baselines, or generating structured
  test reports.
  Triggers on: contract test, Pact, OpenAPI validation, API regression,
  test baseline, test report, consumer-driven, provider verification.
---

# API Contract Testing Patterns

## When to Use What

| Scenario | Approach |
|---|---|
| Frontend consumes Backend API | Consumer-driven contracts (Pact) |
| Service A calls Service B | Consumer-driven contracts (Pact) |
| Public API with OpenAPI spec | OpenAPI validation + contract tests |
| API endpoint regression | Baseline comparison |
| Pre-release validation | Smoke tests + contract tests + baseline |

## OpenAPI Spec Validation

### Select the target and installed validator

The former inline OpenAPI loop is retired: checking a few required keys is not schema validation. Use the repository's installed contract runner, compatible with its OpenAPI version and JSON Schema dialect. Follow [the installed-validator workflow](references/installed-validator-workflow.md) for discovery, explicit target selection, negative fixtures, baseline comparison and result gates.

1. Identify the service/module, current working changes, spec path/hash and test environment from the task and project manifests. Use file discovery; do not fetch a guessed localhost endpoint or automatically execute operations found in a spec.
2. Read the lockfile, existing contract tests and CI command. Inspect the installed validator's version/help and its matching documentation. Spec linting alone does not validate HTTP responses.
3. Run the existing tests with deterministic request fixtures. Assert the scenario's **exact expected status**, content type and complete response schema through the real validator, including `$ref` and nested constraints. A declared 401 response is not success for a scenario expecting 200.
4. Missing tool/test target, incompatible dialect, unresolved reference, parse error or no tests executed means **unsupported/failed**, never a contract PASS. Preserve the runner's nonzero failure status.
5. No installations, service startup or mutating HTTP replay follows from discovery. Use in-process fixtures or an explicitly authorized disposable target; identify it in the report. Redact credentials and payload values from artifacts.

## Consumer-Driven Contracts (Pact)

### Spring Boot Provider (Pact JVM)

```xml
<!-- pom.xml -->
<dependency>
  <groupId>au.com.dius.pact.provider</groupId>
  <artifactId>junit5spring</artifactId>
  <version>4.6.x</version>
  <scope>test</scope>
</dependency>
```

```java
// Provider verification test
@Provider("account-service")
@PactBroker(url = "${PACT_BROKER_URL}")  // or @PactFolder("pacts") for local
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountServicePactVerificationTest {

    @LocalServerPort
    int port;

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @BeforeEach
    void setUp(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    // State handlers — set up test data for each pact state
    @State("account 12345 exists with balance 50000")
    void accountExists() {
        // Insert test data matching the state description
        accountRepository.save(new Account("12345", 50000L, "SGD"));
    }

    @State("account 99999 does not exist")
    void accountNotFound() {
        accountRepository.deleteById("99999");
    }
}
```

### Go Provider (Pact Go)

```go
// provider_test.go
func TestPactProvider(t *testing.T) {
    // Start test server
    srv := httptest.NewServer(setupRouter())
    defer srv.Close()

    verifier := native.HTTPVerifier{}

    err := verifier.VerifyProvider(t, native.VerifyRequest{
        ProviderBaseURL: srv.URL,
        Provider:        "transaction-service",
        PactDirs:        []string{"./pacts"}, // or PactBrokerURL
        StateHandlers: native.StateHandlers{
            "user has 3 transactions": func(setup bool, s native.ProviderStateV3) (native.ProviderStateV3Response, error) {
                if setup {
                    seedTestTransactions(3)
                }
                return nil, nil
            },
        },
    })

    assert.NoError(t, err)
}
```

### Consumer Contract (TypeScript/Frontend)

```typescript
// consumer.pact.test.ts — frontend or calling service
import { PactV3, MatchersV3 } from '@pact-foundation/pact';

const provider = new PactV3({
  consumer: 'web-frontend',
  provider: 'account-service',
  dir: './pacts',
});

describe('Account API', () => {
  it('returns account balance', async () => {
    await provider
      .given('account 12345 exists with balance 50000')
      .uponReceiving('a request for account balance')
      .withRequest({
        method: 'GET',
        path: '/api/accounts/12345/balance',
        headers: { Authorization: MatchersV3.like('Bearer token') },
      })
      .willRespondWith({
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: {
          data: {
            accountId: '12345',
            balance: MatchersV3.integer(50000),
            currency: MatchersV3.string('SGD'),
          },
          meta: {
            timestamp: MatchersV3.iso8601DateTime(),
          },
        },
      })
      .executeTest(async (mockServer) => {
        const client = new AccountClient(mockServer.url);
        const balance = await client.getBalance('12345');
        expect(balance.accountId).toBe('12345');
      });
  });

  it('returns 404 for unknown account', async () => {
    await provider
      .given('account 99999 does not exist')
      .uponReceiving('a request for non-existent account')
      .withRequest({
        method: 'GET',
        path: '/api/accounts/99999/balance',
      })
      .willRespondWith({
        status: 404,
        body: {
          error: {
            code: 'RESOURCE_NOT_FOUND',
            message: MatchersV3.string(),
          },
        },
      })
      .executeTest(async (mockServer) => {
        const client = new AccountClient(mockServer.url);
        await expect(client.getBalance('99999')).rejects.toThrow();
      });
  });
});
```

## Baseline Regression Testing

### Repository-owned baseline comparison

The inline capture/replay/comparison scripts are retired. Use the same deterministic project tests and schema validator as the contract gate, with sanitized version-controlled fixtures or the project's installed snapshot facility. See [the workflow](references/installed-validator-workflow.md#baseline-regression) for required request and comparison fields.

A baseline must retain the scenario's method, resolved path, query, headers excluding secrets, request body/encoding, fixture setup and expected status/content type. Replaying POST with no body is a different test. Compare nested values/arrays according to explicit stable-field rules, and run the complete schema validation again. Never silently replace malformed JSON with null or compare only top-level keys. Treat missing/empty baseline sets as an incomplete gate; update accepted snapshots only after reviewing the behavioral change.

Timing is a separate measurement: use repeated samples and the project's latency budget under controlled load. A single baseline timing ratio is not proof of a functional regression.

## Structured Test Report Format

```json
{
  "report": {
    "timestamp": "2024-01-15T10:30:00Z",
    "environment": "staging",
    "base_url": "http://localhost:8080",
    "duration_seconds": 45.2,
    "summary": {
      "total_tests": 24,
      "passed": 20,
      "failed": 3,
      "warnings": 1,
      "pass_rate": "83.3%"
    },
    "by_category": {
      "smoke": { "passed": 10, "failed": 0 },
      "contract": { "passed": 5, "failed": 2 },
      "security": { "passed": 4, "failed": 1 },
      "regression": { "passed": 1, "failed": 0, "warnings": 1 }
    },
    "failures": [
      {
        "test": "POST /api/transfers — contract validation",
        "expected": "response contains 'transactionId' field",
        "actual": "field 'transactionId' missing from response",
        "severity": "high",
        "category": "contract"
      }
    ],
    "recommendations": [
      "POST /api/transfers response missing 'transactionId' — breaking change for frontend consumer",
      "GET /api/transactions response time degraded 3x from baseline — investigate query performance"
    ]
  }
}
```

### Report evidence

Prefer the installed runner's JUnit/JSON output and exit status. Do not synthesize empty successful categories when an input report is absent. Mark each category `executed`, `failed`, `skipped`, `unsupported` or `static-only`; include tool/version, spec hash, scenario selectors, target and actual test counts. Keep the illustrative JSON above as a report shape, not generated evidence. Missing results or zero executed contract scenarios cannot pass a required gate.

## Common Mistakes to Avoid

1. **Testing exact values instead of structure** — contracts should validate shape (field exists, type correct), not exact values (which change per environment).
2. **Missing state handlers** — Pact provider tests fail without proper state setup. Every `given()` needs a `@State` handler.
3. **Not versioning contracts** — always publish with consumer version. Use `can-i-deploy` before releasing.
4. **Conflating timing with schema compliance** — test latency against the project budget separately; one timing sample cannot certify either.
5. **Contract tests replacing integration tests** — contracts verify the interface, not the business logic. Both are needed.
6. **Hardcoded auth tokens in scripts** — use environment variables or token refresh scripts.
