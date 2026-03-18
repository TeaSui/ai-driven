# impl-02: MDC Correlation IDs

**Status:** ✅ Complete  
**Priority:** 1.2  
**Impact:** Every log line now carries a correlation ID and ticket key for tracing

---

## Changes

- [x] Create `LambdaCorrelationContext.java` utility in `core` module
- [x] Integrate into `JiraWebhookHandler`
- [x] Integrate into `FetchTicketHandler`
- [x] Integrate into `BitbucketFetchHandler`
- [x] Integrate into `ClaudeInvokeHandler`
- [x] Integrate into `PrCreatorHandler`
- [x] Integrate into `MergeWaitHandler`

## MDC Keys

| Key | Value | Source |
|-----|-------|--------|
| `correlationId` | UUID (8-char prefix) | Generated per invocation |
| `ticketKey` | e.g. `PROJ-123` | Extracted from handler input |
| `handler` | e.g. `ClaudeInvokeHandler` | Passed by each handler |

## Usage Pattern

```java
public Map handleRequest(Map input, Context context) {
    LambdaCorrelationContext.init("MyHandler", input);
    try {
        // ... handler logic — all log.info/warn/error calls now include MDC keys
    } finally {
        LambdaCorrelationContext.clear(); // prevent stale data on container reuse
    }
}
```

## Files Modified

- `application/core/src/main/java/com/aidriven/core/util/LambdaCorrelationContext.java` *(new)*
- All 6 handlers in `application/spring-boot-app/src/main/java/com/aidriven/lambda/`
- `application/spring-boot-app/src/main/resources/log4j2.xml` *(new)*

## Logging Framework

Stateful MDC propagation requires a robust logging implementation. We switched from `slf4j-simple` to **Log4j2** (`aws-lambda-java-log4j2`) because `slf4j-simple` does not support MDC.

The log format is defined in `log4j2.xml`:
```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss} %X{correlationId} %X{ticketKey} %-5p %c{1} - %m%n</pattern>
```
