---
name: bdd-cucumber
description: |
  BDD with Cucumber: writing Gherkin feature files, step definitions for Java (Cucumber-JVM) and Go (godog),
  scenario outlines, data tables, hooks, and linking features to acceptance criteria.
  Use when: writing Cucumber tests, creating Gherkin feature files, implementing step definitions,
  setting up BDD test infrastructure, or converting acceptance criteria to scenarios.
  Triggers on: Cucumber, Gherkin, BDD, feature file, .feature, step definitions, scenario outline, godog.
---

# BDD Cucumber Patterns

## Gherkin Writing Rules

### 1. Business language, not implementation details

```gherkin
# WRONG — implementation details
Scenario: Create order via POST endpoint
  Given the database has a product with id 1 and price 100.00
  When I send a POST request to /api/orders with JSON body {"productId": 1, "quantity": 2}
  Then the response status code is 201
  And the response body contains "orderId"

# RIGHT — business behavior
Scenario: Customer places an order
  Given a product "Widget" is available at $100.00
  When the customer orders 2 units of "Widget"
  Then the order is created with total $200.00
  And the customer receives an order confirmation
```

### 2. One behavior per scenario
Each scenario tests ONE business rule. If you need "And" more than twice in Then, you're testing multiple things.

### 3. Given-When-Then structure
- **Given** = precondition (system state before action)
- **When** = action (exactly ONE action per scenario)
- **Then** = observable outcome (verify from user's perspective)

### 4. Declarative over imperative
```gherkin
# WRONG — imperative (how)
Given I navigate to the login page
And I enter "user@test.com" in the email field
And I enter "password123" in the password field
And I click the login button

# RIGHT — declarative (what)
Given I am logged in as "user@test.com"
```

## Feature File Structure

```gherkin
@orders @regression
Feature: Order Management
  As a customer
  I want to place orders for products
  So that I can purchase items I need

  # Link to acceptance criteria from BA specs
  # See: docs/requirements/order-management.md

  Background:
    Given the product catalog contains:
      | name   | price  | stock |
      | Widget | 100.00 | 50    |
      | Gadget | 250.00 | 10    |

  @happy-path @P0
  Scenario: Customer places a simple order
    When the customer orders 2 units of "Widget"
    Then the order is created with total $200.00
    And the product "Widget" stock is reduced to 48

  @validation @P1
  Scenario: Order rejected when product out of stock
    Given the product "Gadget" has 0 stock
    When the customer orders 1 unit of "Gadget"
    Then the order is rejected with reason "insufficient stock"

  @P1
  Scenario Outline: Order total calculated with quantity
    When the customer orders <quantity> units of "<product>"
    Then the order is created with total $<total>

    Examples:
      | product | quantity | total  |
      | Widget  | 1        | 100.00 |
      | Widget  | 5        | 500.00 |
      | Gadget  | 2        | 500.00 |
```

### Tagging Strategy
| Tag | Purpose |
|-----|---------|
| `@P0` | Critical path — must pass before any deployment |
| `@P1` | Important — must pass before release |
| `@P2` | Edge cases — should pass, non-blocking |
| `@wip` | Work in progress — excluded from CI |
| `@slow` | Long-running — may run only nightly |
| Feature tags (`@orders`) | Filter by domain |

## Data Tables

```gherkin
# List of items
Given the following products exist:
  | name   | price  | category    |
  | Widget | 100.00 | electronics |
  | Gadget | 250.00 | electronics |

# Key-value pairs (single column = field/value)
Given an order with details:
  | field    | value      |
  | customer | John Doe   |
  | product  | Widget     |
  | quantity | 3          |
```

## Step Definitions — Java (Cucumber-JVM)

### Setup (build.gradle)
```groovy
dependencies {
    testImplementation 'io.cucumber:cucumber-java:7.+'
    testImplementation 'io.cucumber:cucumber-spring:7.+'
    testImplementation 'io.cucumber:cucumber-junit-platform-engine:7.+'
}

tasks.named('test') {
    systemProperty 'cucumber.plugin', 'pretty,html:build/reports/cucumber.html'
    systemProperty 'cucumber.glue', 'com.example.steps'
    systemProperty 'cucumber.features', 'src/test/resources/features'
}
```

### Spring Integration
```java
// CucumberSpringConfig.java — one config class, shared across all step defs
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class CucumberSpringConfig {
    // Spring context boots once, reused across all scenarios
}
```

### Step Definition Patterns
```java
public class OrderSteps {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ProductRepository productRepository;

    // Shared state within a scenario — use a scenario-scoped holder
    private ResponseEntity<OrderResponse> lastResponse;

    @Given("a product {string} is available at ${double}")
    public void productAvailable(String name, double price) {
        productRepository.save(new Product(name, BigDecimal.valueOf(price), 100));
    }

    @Given("the product catalog contains:")
    public void productCatalogContains(DataTable table) {
        table.asMaps().forEach(row -> {
            productRepository.save(new Product(
                row.get("name"),
                new BigDecimal(row.get("price")),
                Integer.parseInt(row.get("stock"))
            ));
        });
    }

    @When("the customer orders {int} units of {string}")
    public void customerOrders(int quantity, String productName) {
        var request = new CreateOrderRequest(productName, quantity);
        lastResponse = restTemplate.postForEntity("/api/orders", request, OrderResponse.class);
    }

    @Then("the order is created with total ${double}")
    public void orderCreatedWithTotal(double expectedTotal) {
        assertThat(lastResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lastResponse.getBody().getTotal())
            .isEqualByComparingTo(BigDecimal.valueOf(expectedTotal));
    }

    @Then("the order is rejected with reason {string}")
    public void orderRejectedWithReason(String reason) {
        assertThat(lastResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(lastResponse.getBody().getError().getMessage()).contains(reason);
    }
}
```

### Hooks (Java)
```java
public class ScenarioHooks {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Before
    public void cleanDatabase() {
        // Clean test data before each scenario — use TRUNCATE for speed
        jdbcTemplate.execute("TRUNCATE TABLE orders, products RESTART IDENTITY CASCADE");
    }

    @After
    public void captureFailure(Scenario scenario) {
        if (scenario.isFailed()) {
            // Attach diagnostic info to report
            scenario.attach(getLastResponseBody(), "application/json", "API Response");
        }
    }

    @Before("@slow")
    public void setExtendedTimeout() {
        // Tag-specific hooks
    }
}
```

## Step Definitions — Go (godog)

### Setup
```go
// features/order_test.go
func TestFeatures(t *testing.T) {
    suite := godog.TestSuite{
        ScenarioInitializer: InitializeScenario,
        Options: &godog.Options{
            Format:   "pretty",
            Paths:    []string{"features"},
            TestingT: t,
        },
    }
    if suite.Run() != 0 {
        t.Fatal("non-zero status returned")
    }
}

func InitializeScenario(ctx *godog.ScenarioContext) {
    steps := &OrderSteps{}

    ctx.Before(func(ctx context.Context, sc *godog.Scenario) (context.Context, error) {
        steps.reset()
        return ctx, nil
    })

    ctx.Step(`^a product "([^"]*)" is available at \$(\d+\.\d+)$`, steps.productAvailable)
    ctx.Step(`^the customer orders (\d+) units of "([^"]*)"$`, steps.customerOrders)
    ctx.Step(`^the order is created with total \$(\d+\.\d+)$`, steps.orderCreatedWithTotal)
}
```

### Step Definition Pattern (Go)
```go
type OrderSteps struct {
    server   *httptest.Server
    response *http.Response
    body     []byte
}

func (s *OrderSteps) reset() {
    s.response = nil
    s.body = nil
}

func (s *OrderSteps) productAvailable(name string, price float64) error {
    // Set up test data
    _, err := s.db.Exec("INSERT INTO products (name, price, stock) VALUES ($1, $2, 100)", name, price)
    return err
}

func (s *OrderSteps) customerOrders(quantity int, product string) error {
    reqBody, _ := json.Marshal(map[string]any{"product": product, "quantity": quantity})
    resp, err := http.Post(s.server.URL+"/api/orders", "application/json", bytes.NewReader(reqBody))
    if err != nil {
        return fmt.Errorf("POST /api/orders: %w", err)
    }
    s.response = resp
    s.body, _ = io.ReadAll(resp.Body)
    return nil
}

func (s *OrderSteps) orderCreatedWithTotal(expectedTotal float64) error {
    if s.response.StatusCode != http.StatusCreated {
        return fmt.Errorf("expected 201, got %d: %s", s.response.StatusCode, s.body)
    }
    var result struct{ Data struct{ Total float64 } }
    if err := json.Unmarshal(s.body, &result); err != nil {
        return err
    }
    if result.Data.Total != expectedTotal {
        return fmt.Errorf("expected total %.2f, got %.2f", expectedTotal, result.Data.Total)
    }
    return nil
}
```

## Linking to Acceptance Criteria

When BA specs exist in `docs/requirements/`, map scenarios directly:

```gherkin
# AC-1: Customer can place an order for available products
# AC-2: Order is rejected when product is out of stock
# AC-3: Order total is calculated as sum of (price x quantity) per line item

@AC-1
Scenario: Customer places an order for available product
  ...

@AC-2
Scenario: Order rejected for out-of-stock product
  ...

@AC-3
Scenario Outline: Order total calculation
  ...
```

## Common Mistakes

1. **Testing implementation, not behavior** — "When I call POST /api/orders" vs "When the customer places an order."
2. **Too many steps per scenario** — if a scenario has 10+ steps, it's testing too many things. Split it.
3. **Shared mutable state across scenarios** — each scenario must be independent. Use `@Before` hooks to reset state.
4. **Step definitions with logic** — steps should delegate to helper methods or service calls. The step is glue, not implementation.
5. **Missing Background** — if 3+ scenarios share the same Given steps, use Background to reduce duplication.
6. **No tags** — without tags you can't run subsets (P0 only, by domain, skip @wip). Tag from day one.
7. **Scenario Outline with one example** — if there's only one example, use a plain Scenario. Outlines are for parameterized cases.
