# impl-03: Constructor Standardization

**Status:** ✅ Complete (pattern updated)  
**Priority:** 1.3  
**Impact:** All handlers now have consistent DI pattern for testability

---

## Changes

- [x] Migrated to `@RequiredArgsConstructor` (or equivalent explicit DI) across all handlers
- [x] Ensure `@RequiredArgsConstructor` is supplemented by an explicit no-arg constructor for AWS Lambda runtime

## Pattern

> **Update:** The original dual-constructor pattern described below has been
> superseded by Lombok `@RequiredArgsConstructor`. Each handler keeps an
> explicit no-arg constructor for the AWS Lambda runtime and uses
> `@RequiredArgsConstructor` for test injection:

```java
@RequiredArgsConstructor
public class MyHandler implements RequestHandler<...> {

    private final Client client;
    private final String config;

    // No-arg constructor — used by AWS Lambda runtime
    public MyHandler() {
        ServiceFactory factory = ServiceFactory.getInstance();
        this.client = factory.getClient();
        this.config = factory.getAppConfig().getMyConfig();
    }
}
```

> **Decision:** Dagger 2 DI framework was rejected — adds cold start overhead and complexity for a small codebase. Lombok `@RequiredArgsConstructor` provides adequate testability with minimal boilerplate.

## Files Modified

- `application/spring-boot-app/src/main/java/com/aidriven/lambda/FetchTicketHandler.java`
- `application/spring-boot-app/src/main/java/com/aidriven/lambda/BitbucketFetchHandler.java`
- `application/spring-boot-app/src/main/java/com/aidriven/lambda/ClaudeInvokeHandler.java`
- `application/spring-boot-app/src/main/java/com/aidriven/lambda/PrCreatorHandler.java`
- `application/spring-boot-app/src/main/java/com/aidriven/lambda/JiraWebhookHandler.java`
- `application/spring-boot-app/src/main/java/com/aidriven/lambda/MergeWaitHandler.java`

