---
name: backend-languages-spring-boot
description: >
  FOR BACKEND-ENGINEER-SUBAGENT USE ONLY.
  Spring Boot patterns: layered architecture (Controller/Service/Repository), profile management,
  constructor injection, @ControllerAdvice exception handling, test slicing, @ConfigurationProperties,
  Spring Security filter chain.
type: reference
---

# Spring Boot Patterns

## When to use

Triggered by: `@SpringBootApplication`, `@RestController`, `build.gradle`, `pom.xml`, Spring MVC, JPA, Hibernate, Spring Boot.

## Project layout

```
src/main/java/com/example/myservice/
├── MyServiceApplication.java          # @SpringBootApplication — nothing else here
├── config/                            # @Configuration, SecurityFilterChain, WebMvcConfigurer
├── controller/                        # @RestController — thin, delegates to service
│   └── dto/                           # Request/Response DTOs (validated here)
├── service/                           # @Service — business logic, @Transactional boundaries
│   └── impl/                          # Implementations (only if interface needed)
├── repository/                        # @Repository — Spring Data JPA interfaces
├── entity/                            # @Entity — JPA entities, no business logic
├── exception/                         # Custom exceptions + @ControllerAdvice handler
├── mapper/                            # MapStruct or manual entity<->DTO mappers
└── client/                            # External service clients (RestClient, WebClient)

src/main/resources/
├── application.yml                    # Shared defaults
├── application-local.yml              # Local dev overrides
├── application-test.yml               # Test overrides
├── application-prod.yml               # Prod (secrets from env vars / Secrets Manager)
└── db/migration/                      # Flyway migrations (V1__description.sql)
```

**Layered rules:**
1. Controllers validate input, call service, return response. No business logic.
2. Services own business logic and `@Transactional` boundaries. Never call other controllers.
3. Repositories extend `JpaRepository<Entity, ID>`; complex queries use `@Query` with JPQL or native SQL.
4. Entities are persistence models only — never expose directly in API responses; map to DTOs.
5. No circular dependencies — extract shared logic to a third service if needed.

## Error handling

Use central `@RestControllerAdvice` with the canonical `{ "error": { "code": "...", "message": "...", "details": [] } }` envelope from `rules/patterns.md`, unless an explicit project contract overrides it. Keep the correct HTTP status; never catch exceptions in individual controllers.

Java 17+ / Spring MVC example (application DTOs, not Spring `ProblemDetail`):

```java
public record FieldErrorDetail(String field, String message) {}
public record ApiError(String code, String message, List<FieldErrorDetail> details) {}
public record ApiErrorResponse(ApiError error) {}

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(
            new ApiError("RESOURCE_NOT_FOUND", "Resource not found", List.of())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        var details = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new FieldErrorDetail(e.getField(), "Invalid value"))
            .toList(); // no rejected values or exception messages in the response
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
            new ApiError("VALIDATION_ERROR", "Validation failed", details)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error; category={}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
            new ApiError("INTERNAL_ERROR", "Internal server error", List.of())));
    }
}
```

Place each public record/class in its own file; import `java.util.List`, `org.slf4j.Logger`/`LoggerFactory`, `org.springframework.http.HttpStatus`/`ResponseEntity`, `org.springframework.web.bind.MethodArgumentNotValidException`, and the `org.springframework.web.bind.annotation` advice/handler annotations. Adapt safe field labels/messages to the project's public validation contract. For complete envelope coverage, map other relevant MVC exceptions and Spring Security's authentication/access-denied handlers at their boundary; advice does not automatically handle filter-chain errors. Verify 404/400/500 status plus `$.error.code`, `$.error.message` and `$.error.details` with the project's MVC tests.

RFC 9457 `ProblemDetail` is a project-selected alternative. Its properties serialize at top level, so adding `code` does not produce `error.code`; do not substitute it for the canonical envelope implicitly. See [Spring error responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html).

Custom exception hierarchy:
```java
public abstract class BusinessException extends RuntimeException {
    private final String code;
    protected BusinessException(String code, String message) { super(message); this.code = code; }
}

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resource, Object id) {
        super("RESOURCE_NOT_FOUND", "%s with id %s not found".formatted(resource, id));
    }
}
```

## Testing

| What to test | Annotation | Loads | Speed |
|---|---|---|---|
| Controller routing, validation, serialization | `@WebMvcTest(FooController.class)` | Web layer only | Fast |
| JPA repository queries | `@DataJpaTest` | JPA + embedded DB | Fast |
| Full request through all layers | `@SpringBootTest` + `@AutoConfigureMockMvc` | Everything | Slow |
| Single service class | Plain JUnit + Mockito (no Spring) | Nothing | Fastest |
| External client | `@RestClientTest(FooClient.class)` | RestClient + MockRestServiceServer | Fast |

```java
@WebMvcTest(OrderController.class)
class OrderControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private OrderService orderService;

    @Test
    void createOrder_validRequest_returns201() throws Exception {
        when(orderService.createOrder(any())).thenReturn(new OrderResponse(1L, "CREATED"));

        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"productId": 1, "quantity": 2}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("CREATED"));
    }
}

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OrderRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private OrderRepository orderRepository;

    @Test
    void findByStatus_returnsMatchingOrders() {
        orderRepository.save(new Order("PENDING"));
        orderRepository.save(new Order("COMPLETED"));
        assertThat(orderRepository.findByStatus("PENDING")).hasSize(1);
    }
}
```

## Concurrency

Spring Boot services are stateless by design; HTTP threads are managed by Tomcat/Undertow. For async work:

```java
@Service
public class OrderService {

    @Async("taskExecutor")  // requires @EnableAsync + ThreadPoolTaskExecutor @Bean
    public CompletableFuture<Void> sendConfirmationAsync(Order order) {
        emailClient.sendConfirmation(order);
        return CompletableFuture.completedFuture(null);
    }
}
```

- `@Transactional` does NOT propagate across `@Async` boundaries — each async method runs in its own transaction.
- For reactive stacks use Spring WebFlux + `Mono`/`Flux`; do not mix blocking JPA calls into reactive pipelines.

## HTTP handler patterns

```java
@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {
    private final OrderService orderService;
    private final OrderMapper orderMapper;

    public OrderController(OrderService orderService, OrderMapper orderMapper) {
        this.orderService = orderService;
        this.orderMapper = orderMapper;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.of(response));
    }
}
```

**Bean wiring — always constructor injection:**
```java
@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;

    // Spring injects automatically — no @Autowired needed for single constructor
    public OrderService(OrderRepository orderRepository, PaymentClient paymentClient) {
        this.orderRepository = orderRepository;
        this.paymentClient = paymentClient;
    }
}
```

**`@Transactional` rules:**
1. On service methods, not repositories (already transactional per-method).
2. `@Transactional(readOnly = true)` for read methods.
3. Only unchecked exceptions trigger rollback by default.
4. Does NOT work on `private` methods or self-invocation (bypasses proxy).

## Dependency management

```java
// @ConfigurationProperties for typed config — not scattered @Value
@ConfigurationProperties(prefix = "app.payment")
public record PaymentProperties(String baseUrl, Duration timeout, int maxRetries) {}
```

```yaml
# application.yml
app:
  payment:
    timeout: 5s
    max-retries: 3

# application-prod.yml
app:
  payment:
    base-url: ${PAYMENT_SERVICE_URL}  # from env var or Secrets Manager
```

**Spring Security (explicit Authorization-header-only resource server):**

This Spring Security 6+ configuration uses an ordered `/api/**` chain plus a final deny-all chain for every other route. The API chain assumes its bearer-token resolver accepts only caller-supplied Authorization headers. It accepts no JWT/session cookies, HTTP Basic, form login or other browser-automatic credentials. Keep CSRF protection on chains that do accept such credentials, including cookie-based login/refresh/logout, even with `STATELESS`. Add approved browser flows as separately scoped chains before the final catch-all; do not leave unmatched requests outside Spring Security. See [Spring CSRF guidance](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html) and [chain ordering and matcher coverage](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html#_multiple_httpsecurity_instances).

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/api/**")
            .csrf(csrf -> csrf.disable()) // only under the header-only precondition above
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public SecurityFilterChain defaultDenyAllChain(HttpSecurity http) throws Exception {
        return http
            // No securityMatcher: retain the default match-every-request coverage.
            .csrf(Customizer.withDefaults()) // enabled on the catch-all chain
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
            .build();
    }
}
```

Spring Security selects the first matching chain; a scoped chain's `anyRequest()` covers only that chain. A request matching no chain is unprotected. Keep the catch-all last when integrating with existing configuration, and verify that new chains cannot bypass the intended coverage. Add project MVC/security integration checks for an unauthenticated protected API request, an unauthorized admin request, non-API paths with and without authentication, and unsafe non-API requests with/without CSRF. The non-API requests must remain denied; token validity must not bypass the catch-all. This snippet is source-reviewed, not an executed security test or an instruction to upgrade Spring.

## Common pitfalls

1. **`@SpringBootTest` for everything** — use test slices. Full context tests are slow integration tests.
2. **Business logic in controllers** — controllers validate + delegate; logic belongs in services.
3. **Exposing entities as API responses** — always map to DTOs; entity changes must not break API contracts.
4. **`@Autowired` on fields** — use constructor injection; fields are untestable without Spring context.
5. **Missing `@Transactional(readOnly = true)`** — read methods miss Hibernate flush-mode optimizations.
6. **Self-invocation with `@Transactional`** — same-class calls bypass the proxy. Extract to another service or use `TransactionTemplate`.
7. **Catching exceptions in controllers** — use `@ControllerAdvice`; per-controller catch blocks are unmaintainable.
8. **Hardcoded config** — use `@ConfigurationProperties` with profile-specific YAML; secrets from env/Secrets Manager.
