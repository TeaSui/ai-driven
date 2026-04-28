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

### Discovery
```bash
# Find OpenAPI/Swagger specs in project
# Spring Boot: springdoc-openapi generates at runtime
# Go: may have static openapi.yaml or generated via swag

# Spring Boot — fetch live spec
curl -s http://localhost:8080/v3/api-docs | jq . > openapi-spec.json

# Go — check for static spec
find . -name "openapi.yaml" -o -name "openapi.json" -o -name "swagger.yaml" 2>/dev/null
```

### Validation Script
```bash
#!/bin/bash
# api-test-contract-openapi.sh — Validate API responses match OpenAPI spec

SPEC_FILE="${1:-openapi-spec.json}"
BASE_URL="${2:-http://localhost:8080}"
PASS=0
FAIL=0
ERRORS=()

# Extract endpoints from spec
ENDPOINTS=$(jq -r '.paths | to_entries[] | .key' "$SPEC_FILE")

for path in $ENDPOINTS; do
  # Get methods for this path
  METHODS=$(jq -r ".paths[\"$path\"] | keys[]" "$SPEC_FILE" | grep -v parameters)

  for method in $METHODS; do
    METHOD_UPPER=$(echo "$method" | tr '[:lower:]' '[:upper:]')

    # Skip OPTIONS
    [[ "$METHOD_UPPER" == "OPTIONS" ]] && continue

    # Build URL (replace path params with test values)
    URL="${BASE_URL}${path}"
    URL=$(echo "$URL" | sed 's/{[^}]*}/test-id-123/g')

    # Get expected success status code
    EXPECTED_STATUS=$(jq -r ".paths[\"$path\"][\"$method\"].responses | keys[0]" "$SPEC_FILE")

    # Make request
    RESPONSE=$(curl --silent --write-out "\n%{http_code}" \
      --request "$METHOD_UPPER" \
      --header "Content-Type: application/json" \
      --header "Authorization: Bearer ${AUTH_TOKEN}" \
      --max-time 10 \
      "$URL" 2>/dev/null)

    ACTUAL_STATUS=$(echo "$RESPONSE" | tail -1)
    BODY=$(echo "$RESPONSE" | sed '$d')

    # Validate response structure against schema
    RESPONSE_SCHEMA=$(jq ".paths[\"$path\"][\"$method\"].responses[\"$EXPECTED_STATUS\"].content[\"application/json\"].schema" "$SPEC_FILE")

    if [[ "$RESPONSE_SCHEMA" != "null" ]]; then
      # Check required fields exist
      REQUIRED=$(echo "$RESPONSE_SCHEMA" | jq -r '.required[]?' 2>/dev/null)
      for field in $REQUIRED; do
        HAS_FIELD=$(echo "$BODY" | jq "has(\"$field\")" 2>/dev/null)
        if [[ "$HAS_FIELD" != "true" ]]; then
          FAIL=$((FAIL + 1))
          ERRORS+=("$METHOD_UPPER $path: missing required field '$field'")
          continue 2
        fi
      done
    fi

    PASS=$((PASS + 1))
    echo "PASS: $METHOD_UPPER $path (status: $ACTUAL_STATUS)"
  done
done

# Report
echo ""
echo "=== OpenAPI Contract Validation ==="
echo "Pass: $PASS | Fail: $FAIL"
if [[ ${#ERRORS[@]} -gt 0 ]]; then
  echo ""
  echo "Failures:"
  for err in "${ERRORS[@]}"; do
    echo "  - $err"
  done
fi
[[ $FAIL -gt 0 ]] && exit 1 || exit 0
```

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

### Capture Baseline
```bash
#!/bin/bash
# api-test-baseline-capture.sh — Capture response baselines for regression testing

BASE_URL="${1:-http://localhost:8080}"
BASELINE_DIR="api-baselines/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BASELINE_DIR"

# Discover endpoints (reuse from api-test-agent discovery phase)
ENDPOINTS_FILE="api-test-endpoints.json"

capture_baseline() {
  local method="$1" path="$2" body="$3"
  local safe_name=$(echo "${method}_${path}" | tr '/' '_' | tr -d '{}')
  local outfile="${BASELINE_DIR}/${safe_name}.json"

  local response
  if [[ -n "$body" ]]; then
    response=$(curl --silent --write-out '\n{"_status":%{http_code},"_time":%{time_total}}' \
      --request "$method" \
      --header "Content-Type: application/json" \
      --header "Authorization: Bearer ${AUTH_TOKEN}" \
      --data "$body" \
      --max-time 10 \
      "$BASE_URL$path")
  else
    response=$(curl --silent --write-out '\n{"_status":%{http_code},"_time":%{time_total}}' \
      --request "$method" \
      --header "Authorization: Bearer ${AUTH_TOKEN}" \
      --max-time 10 \
      "$BASE_URL$path")
  fi

  local body_response=$(echo "$response" | head -n -1)
  local meta=$(echo "$response" | tail -1)
  local status=$(echo "$meta" | jq -r '._status')
  local time=$(echo "$meta" | jq -r '._time')

  # Store baseline: status, response structure (not exact values), timing
  jq -n \
    --arg method "$method" \
    --arg path "$path" \
    --argjson status "$status" \
    --argjson time "$time" \
    --argjson body "$(echo "$body_response" | jq '.' 2>/dev/null || echo 'null')" \
    '{
      method: $method,
      path: $path,
      status: $status,
      response_time_seconds: $time,
      response_keys: ($body | keys? // []),
      response_structure: ($body | [paths | join(".")] | unique | sort)
    }' > "$outfile"

  echo "Captured: $method $path -> $outfile (status: $status, time: ${time}s)"
}

# Capture baselines for each endpoint
# ... iterate discovered endpoints ...

echo "Baseline captured to: $BASELINE_DIR"
```

### Compare Against Baseline
```bash
#!/bin/bash
# api-test-baseline-compare.sh — Compare current responses against baseline

BASELINE_DIR="${1:?Usage: $0 <baseline_dir>}"
BASE_URL="${2:-http://localhost:8080}"

PASS=0
FAIL=0
WARNINGS=0
RESULTS=()

for baseline_file in "$BASELINE_DIR"/*.json; do
  METHOD=$(jq -r '.method' "$baseline_file")
  PATH_URL=$(jq -r '.path' "$baseline_file")
  EXPECTED_STATUS=$(jq -r '.status' "$baseline_file")
  EXPECTED_KEYS=$(jq -c '.response_keys' "$baseline_file")
  EXPECTED_STRUCTURE=$(jq -c '.response_structure' "$baseline_file")
  BASELINE_TIME=$(jq -r '.response_time_seconds' "$baseline_file")

  # Make current request
  RESPONSE=$(curl --silent --write-out '\n%{http_code} %{time_total}' \
    --request "$METHOD" \
    --header "Authorization: Bearer ${AUTH_TOKEN}" \
    --max-time 10 \
    "$BASE_URL$PATH_URL")

  BODY=$(echo "$RESPONSE" | head -n -1)
  META=$(echo "$RESPONSE" | tail -1)
  ACTUAL_STATUS=$(echo "$META" | awk '{print $1}')
  ACTUAL_TIME=$(echo "$META" | awk '{print $2}')

  RESULT="PASS"
  ISSUES=()

  # Check 1: Status code unchanged
  if [[ "$ACTUAL_STATUS" != "$EXPECTED_STATUS" ]]; then
    RESULT="FAIL"
    ISSUES+=("status changed: $EXPECTED_STATUS -> $ACTUAL_STATUS")
  fi

  # Check 2: Response structure unchanged (no removed fields)
  ACTUAL_KEYS=$(echo "$BODY" | jq -c 'keys? // []' 2>/dev/null)
  MISSING_KEYS=$(jq -n --argjson expected "$EXPECTED_KEYS" --argjson actual "$ACTUAL_KEYS" \
    '$expected - $actual')
  if [[ "$MISSING_KEYS" != "[]" ]]; then
    RESULT="FAIL"
    ISSUES+=("missing response keys: $MISSING_KEYS")
  fi

  # Check 3: Response time regression (>2x baseline = warning, >5x = fail)
  TIME_RATIO=$(echo "$ACTUAL_TIME $BASELINE_TIME" | awk '{if($2>0) print $1/$2; else print 0}')
  if (( $(echo "$TIME_RATIO > 5" | bc -l) )); then
    RESULT="FAIL"
    ISSUES+=("response time 5x+ slower: ${BASELINE_TIME}s -> ${ACTUAL_TIME}s")
  elif (( $(echo "$TIME_RATIO > 2" | bc -l) )); then
    [[ "$RESULT" == "PASS" ]] && RESULT="WARN"
    ISSUES+=("response time 2x+ slower: ${BASELINE_TIME}s -> ${ACTUAL_TIME}s")
    WARNINGS=$((WARNINGS + 1))
  fi

  case "$RESULT" in
    PASS) PASS=$((PASS + 1)); echo "PASS: $METHOD $PATH_URL" ;;
    WARN) echo "WARN: $METHOD $PATH_URL — ${ISSUES[*]}" ;;
    FAIL) FAIL=$((FAIL + 1)); echo "FAIL: $METHOD $PATH_URL — ${ISSUES[*]}" ;;
  esac

  RESULTS+=("$(jq -n \
    --arg method "$METHOD" \
    --arg path "$PATH_URL" \
    --arg result "$RESULT" \
    --argjson issues "$(printf '%s\n' "${ISSUES[@]}" | jq -R . | jq -s .)" \
    '{method: $method, path: $path, result: $result, issues: $issues}'
  )")
done

echo ""
echo "=== Baseline Regression Report ==="
echo "Pass: $PASS | Fail: $FAIL | Warnings: $WARNINGS"
```

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

### Report Generation Script
```bash
#!/bin/bash
# api-test-report.sh — Generate structured JSON report from test results

generate_report() {
  local smoke_results="$1"
  local contract_results="$2"
  local security_results="$3"
  local baseline_results="$4"

  jq -n \
    --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg env "${ENVIRONMENT:-local}" \
    --arg base_url "$BASE_URL" \
    --argjson smoke "$(cat "$smoke_results" 2>/dev/null || echo '{"passed":0,"failed":0}')" \
    --argjson contract "$(cat "$contract_results" 2>/dev/null || echo '{"passed":0,"failed":0}')" \
    --argjson security "$(cat "$security_results" 2>/dev/null || echo '{"passed":0,"failed":0}')" \
    --argjson baseline "$(cat "$baseline_results" 2>/dev/null || echo '{"passed":0,"failed":0}')" \
    '{
      report: {
        timestamp: $timestamp,
        environment: $env,
        base_url: $base_url,
        summary: {
          total_tests: ([$smoke, $contract, $security, $baseline] | map(.passed + .failed) | add),
          passed: ([$smoke, $contract, $security, $baseline] | map(.passed) | add),
          failed: ([$smoke, $contract, $security, $baseline] | map(.failed) | add)
        },
        by_category: {
          smoke: $smoke,
          contract: $contract,
          security: $security,
          regression: $baseline
        }
      }
    }'
}
```

## Common Mistakes to Avoid

1. **Testing exact values instead of structure** — contracts should validate shape (field exists, type correct), not exact values (which change per environment).
2. **Missing state handlers** — Pact provider tests fail without proper state setup. Every `given()` needs a `@State` handler.
3. **Not versioning contracts** — always publish with consumer version. Use `can-i-deploy` before releasing.
4. **Ignoring response time regression** — a 5x slowdown is a functional regression in fintech (payment timeouts).
5. **Contract tests replacing integration tests** — contracts verify the interface, not the business logic. Both are needed.
6. **Hardcoded auth tokens in scripts** — use environment variables or token refresh scripts.
