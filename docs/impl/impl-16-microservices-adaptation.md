# impl-16: Microservices Adaptation (SPI Module System)

**Ticket:** CRM-88
**Status:** Implemented
**Priority:** Medium

## Problem

The project needs to support multiple tenants (companies) with different tool combinations:
- Company A: Jira + GitHub + Claude
- Company B: Linear + Bitbucket + Claude
- Company C: Jira + GitHub + Datadog + Slack

The existing architecture has tight coupling through:
1. `ServiceFactory` — god class that knows about every concrete implementation
2. `AppConfig` — singleton with hardcoded environment variable names
3. Lambda handlers directly instantiate clients via factory
4. No way to compose different module sets per tenant

## Solution: SPI-Based Module System

### New Module: `spi`

A zero-dependency module defining the plugin contract:

```
spi/
  ServiceModule          — lifecycle interface (init, health, shutdown)
  ModuleCategory         — enum (ISSUE_TRACKER, SOURCE_CONTROL, AI_PROVIDER, ...)
  ModuleContext          — tenant config + secrets interface
  ModuleRegistry         — discovery + lifecycle management
  IssueTrackerModule     — typed SPI for issue trackers
  SourceControlModule    — typed SPI for source control
  AiProviderModule       — typed SPI for AI providers
  SimpleModuleContext    — in-memory implementation for testing
```

### Module Implementations

Each client module now implements the SPI:

| Module | SPI Interface | ServiceLoader |
|--------|--------------|---------------|
| `jira-client` | `IssueTrackerModule` | ✅ |
| `bitbucket-client` | `SourceControlModule` | ✅ |
| `github-client` | `SourceControlModule` | ✅ |
| `claude-client` | `AiProviderModule` | ✅ |

### Dependency Graph (After)

```
lambda-handlers
  ├── spi (interfaces only)
  ├── core (shared models)
  ├── jira-client       → core, spi
  ├── bitbucket-client  → core, spi
  ├── github-client     → core, spi
  ├── claude-client     → core, spi
  ├── tool-*            → core
  └── mcp-bridge        → core
```

### Key Design Decisions

1. **ServiceLoader discovery**: Modules register via `META-INF/services/com.aidriven.spi.ServiceModule`
2. **ModuleContext over AppConfig**: Modules receive config through `ModuleContext` interface, not global singletons
3. **Backward compatible**: Existing `ServiceFactory` and `fromSecrets()` patterns still work
4. **Per-tenant composition**: `ModuleRegistry` can be instantiated per tenant with different module sets

### Usage Example

```java
// Per-tenant module composition
ModuleRegistry registry = new ModuleRegistry();
registry.register(new JiraModule());
registry.register(new GitHubModule());
registry.register(new ClaudeModule());

// Initialize with tenant-specific config
ModuleContext ctx = SimpleModuleContext.builder("company-abc")
    .config("baseUrl", "https://company-abc.atlassian.net")
    .secret("email", "bot@company-abc.com")
    .secret("apiToken", "...")
    .build();

registry.initializeAll(ctx);

// Use typed access
IssueTrackerModule jira = registry.getModule("jira", IssueTrackerModule.class);
jira.getClient().getTicket("PROJ-123");
```

### Future Work

- [ ] `TenantConfigResolver` — loads tenant config from DynamoDB/S3
- [ ] `ModuleComposer` — validates required module combinations per tenant
- [ ] Migrate `ServiceFactory` to use `ModuleRegistry` internally
- [ ] Add `LinearModule`, `GitLabModule`, `SlackModule` implementations
- [ ] Per-tenant Lambda routing via API Gateway tenant header
