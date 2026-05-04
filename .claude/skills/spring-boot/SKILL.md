---
name: spring-boot
description: |
  Spring Boot project patterns: layered architecture (Controller/Service/Repository), profile management,
  Bean wiring, @ControllerAdvice exception handling, test slicing (@WebMvcTest, @DataJpaTest, @SpringBootTest),
  @ConfigurationProperties, and Spring Security filter chain configuration.
  Use when: creating Spring Boot services, writing Spring tests, configuring profiles, setting up exception handling,
  or wiring Spring beans. Triggers on: Spring Boot, Spring MVC, JPA, Hibernate, @RestController, Gradle Spring project.
---

# Spring Boot Patterns

## Project Structure

```
src/main/java/com/example/myservice/
├── MyServiceApplication.java          # @SpringBootApplication — nothing else here
├── config/                            # @Configuration beans, SecurityFilterChain, WebMvcConfigurer
├── controller/                        # @RestController — thin, delegates to service
│   └── dto/                           # Request/Response DTOs (validated here)
├── service/                           # @Service — business logic, @Transactional boundaries
│   └── impl/                          # Implementations (only if interface needed for testability/multi-impl)
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

## Layered Architecture Rules

1. **Controllers** are thin — validate input, call service, return response. No business logic.
2. **Services** own business logic and `@Transactional` boundaries. Services call repositories, never other controllers.
3. **Repositories** extend `JpaRepository<Entity, ID>` — use derived queries, `@Query` with JPQL, or native SQL for complex cases.
4. **Entities** are persistence models only — never expose directly in API responses. Map to DTOs.
5. **No circular dependencies** — if Service A needs Service B and vice versa, extract shared logic to Service C.

## Exception Handling (Centralized)

Use `@ControllerAdvice` with RFC 9457 ProblemDetail. Never catch exceptions in individual controllers.

```java
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Resource Not Found");
        pd.setProperty("code", "RESOURCE_NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation Failed");
        pd.setProperty("code", "VALIDATION_ERROR");
        pd.setProperty("details", ex.getBindingResult().getFieldErrors().stream()
            .map(e -> Map.of("field", e.getField(), "message", e.getDefaultMessage()))
            .toList());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        // Log full stack trace server-side, return sanitized response
        log.error("Unexpected error", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal Server Error");
        pd.setProperty("code", "INTERNAL_ERROR");
        return pd;
    }
}
```

Custom exception hierarchy:
```java
public abstract class BusinessException extends RuntimeException {
    private final String code;
    protected BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }
}

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resource, Object id) {
        super("RESOURCE_NOT_FOUND", "%s with id %s not found".formatted(resource, id));
    }
}
```

## Bean Wiring

**Prefer constructor injection.** Spring auto-wires single-constructor beans — no `@Autowired` needed.

```java
@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;
    private final OrderMapper orderMapper;

    // Spring injects automatically — no @Autowired annotation needed
    public OrderService(OrderRepository orderRepository,
                        PaymentClient paymentClient,
                        OrderMapper orderMapper) {
        this.orderRepository = orderRepository;
        this.paymentClient = paymentClient;
        this.orderMapper = orderMapper;
    }
}
```

**When to use `@Bean` methods in `@Configuration`:**
- Third-party classes you can't annotate (RestClient, ObjectMapper, ThreadPoolExecutor)
- Conditional beans (`@ConditionalOnProperty`, `@Profile`)
- Beans requiring complex initialization

```java
@Configuration
public class ClientConfig {

    @Bean
    public RestClient paymentClient(@Value("${payment.base-url}") String baseUrl) {
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }
}
```

## Profile & Configuration

**Use `@ConfigurationProperties` for typed config — not scattered `@Value`.**

```java
@ConfigurationProperties(prefix = "app.payment")
public record PaymentProperties(
    String baseUrl,
    Duration timeout,
    int maxRetries
) {}

// Enable in application class or config
@EnableConfigurationProperties(PaymentProperties.class)
```

```yaml
# application.yml (defaults)
app:
  payment:
    timeout: 5s
    max-retries: 3

# application-local.yml
app:
  payment:
    base-url: http://localhost:8081

# application-prod.yml
app:
  payment:
    base-url: ${PAYMENT_SERVICE_URL}  # from env var or Secrets Manager
```

**Profile activation:**
- Local: `SPRING_PROFILES_ACTIVE=local` (env var or IDE run config)
- Tests: `@ActiveProfiles("test")` on test class
- Prod: `SPRING_PROFILES_ACTIVE=prod` (container env var)

## @Transactional Rules

1. Place `@Transactional` on **service methods**, not repositories (repositories are already transactional per-method).
2. `@Transactional(readOnly = true)` for read-only methods — enables optimizations.
3. Only **unchecked exceptions** (RuntimeException) trigger rollback by default. For checked: `@Transactional(rollbackFor = CheckedException.class)`.
4. `@Transactional` does NOT work on **private methods** or **self-invocation** (same-class calls bypass the proxy).

```java
@Service
public class OrderService {

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        // All DB operations in one transaction
        Order order = orderMapper.toEntity(request);
        order = orderRepository.save(order);
        // If this throws RuntimeException, save is rolled back
        paymentClient.reserve(order.getId(), order.getTotal());
        return orderMapper.toResponse(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listOrders(Pageable pageable) {
        return orderRepository.findAll(pageable).map(orderMapper::toResponse);
    }
}
```

## Test Slicing Decision Table

| What to test | Annotation | What it loads | Speed |
|---|---|---|---|
| Controller routing, validation, serialization | `@WebMvcTest(FooController.class)` | Web layer only | Fast |
| JPA repository queries, custom SQL | `@DataJpaTest` | JPA + embedded DB | Fast |
| Full request through all layers | `@SpringBootTest` + `@AutoConfigureMockMvc` | Everything | Slow |
| Single service class | Plain JUnit + Mockito (no Spring) | Nothing | Fastest |
| External client behavior | `@RestClientTest(FooClient.class)` | RestClient + MockRestServiceServer | Fast |

### @WebMvcTest Example
```java
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OrderService orderService; // mock the service layer

    @Test
    void createOrder_validRequest_returns201() throws Exception {
        when(orderService.createOrder(any())).thenReturn(new OrderResponse(1L, "CREATED"));

        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productId": 1, "quantity": 2}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("CREATED"));
    }

    @Test
    void createOrder_missingProductId_returns400() throws Exception {
        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"quantity": 2}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
```

### @DataJpaTest Example
```java
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

        List<Order> pending = orderRepository.findByStatus("PENDING");
        assertThat(pending).hasSize(1);
    }
}
```

## Spring Security (Configuration Only)

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable()) // Disable for stateless APIs
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**", "/actuator/health").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .build();
    }
}
```

## Common Mistakes to Avoid

1. **`@SpringBootTest` for everything** — use test slices. Full context tests are integration tests, not unit tests.
2. **Business logic in controllers** — controllers validate + delegate. Logic belongs in services.
3. **Exposing entities as API responses** — always map to DTOs. Entity changes should not break API contracts.
4. **`@Autowired` on fields** — use constructor injection. Fields are untestable without Spring context.
5. **Missing `@Transactional(readOnly = true)`** — read methods miss Hibernate flush-mode optimizations.
6. **Self-invocation expecting `@Transactional`** — calling a `@Transactional` method from the same class bypasses the proxy. Extract to another service or use `TransactionTemplate`.
7. **Catching exceptions in controllers** — use `@ControllerAdvice`. Per-controller catch blocks are unmaintainable.
8. **Hardcoded config values** — use `@ConfigurationProperties` with profile-specific YAML files.
