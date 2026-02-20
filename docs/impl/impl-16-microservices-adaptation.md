# impl-16: Microservices Adaptation for Multi-Tenant SaaS

**Ticket:** CRM-88  
**Status:** In Progress  
**Priority:** Medium  

## Problem

The system needs to support multiple companies as a rental/subscription SaaS service. Each company uses different tools (Jira vs Linear, Bitbucket vs GitHub, etc.) and has different configurations. The current architecture is single-tenant with hardcoded module dependencies.

## Solution: Service Provider Interface (SPI) + Tenant-Aware Configuration

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Lambda Handlers                       │
│  (thin entry points — route to correct tenant context)  │
├─────────────────────────────────────────────────────────┤
│                    Core Module                           │
│  (domain logic, agent orchestrator, tool registry)      │
│  + TenantAwareAppConfig, TenantConfigLoader             │
├─────────────────────────────────────────────────────────┤
│                    SPI Module (NEW)                      │
│  AiDrivenModule, ModuleRegistry, ModuleContext          │
│  TenantConfig, HealthCheckResult                        │
├─────────────────────────────────────────────────────────┤
│              Module Implementations                      │
│  bitbucket-client  github-client  jira-client           │
│  claude-client     mcp-bridge     tool-*                │
│  (each implements AiDrivenModule SPI)                   │
└─────────────────────────────────────────────────────────┘
```

### Key Design Decisions

1. **SPI Module (Zero Dependencies)**  
   - New `spi` module with ZERO internal dependencies
   - Defines `AiDrivenModule` interface, `ModuleRegistry`, `ModuleContext`
   - External integrators only need this module to build custom modules

2. **ServiceLoader Discovery**  
   - Modules are discovered via `java.util.ServiceLoader`
   - Drop a JAR on the classpath → module is auto-registered
   - No code changes needed to add new integrations

3. **Tenant-Aware Configuration**  
   - `TenantConfig` — per-tenant module bindings, config, and secrets
   - `TenantAwareAppConfig` — overlays tenant overrides on base config
   - `TenantConfigLoader` — loads and caches tenant configs
   - Backward compatible: default tenant = existing single-tenant behavior

4. **Module Lifecycle**  
   - `initialize(ModuleContext)` — receives tenant-specific config
   - `healthCheck()` — periodic health verification
   - `shutdown()` — graceful cleanup

### Module Dependency Graph (After)

```
lambda-handlers
  ├── core → spi
  ├── jira-client → core → spi
  ├── bitbucket-client → core → spi
  ├── github-client → core → spi
  ├── claude-client → core → spi
  ├── tool-* → core → spi
  └── mcp-bridge → core → spi

spi (ZERO internal dependencies)
  └── jackson-databind (only external dep)
```

### Tenant Configuration Example

```json
{
  "tenantId": "acme-corp",
  "modules": {
    "source-control": "github",
    "issue-tracker": "jira",
    "ai-provider": "claude"
  },
  "config": {
    "github.owner": "acme-corp",
    "github.repo": "backend",
    "jira.baseUrl": "https://acme.atlassian.net",
    "claude.model": "claude-sonnet-4-5",
    "branch.prefix": "acme/ai/"
  },
  "secrets": {
    "github.token": "arn:aws:secretsmanager:...:github-token",
    "jira.credentials": "arn:aws:secretsmanager:...:jira-creds",
    "claude.apiKey": "arn:aws:secretsmanager:...:claude-key"
  }
}
```

### Migration Path

1. **Phase 1 (This PR):** Add SPI module, ModuleRegistry, TenantConfig layer
2. **Phase 2:** Implement `AiDrivenModule` in existing clients (BitbucketClient, JiraClient, etc.)
3. **Phase 3:** Add DynamoDB-backed tenant config store
4. **Phase 4:** Multi-tenant Lambda routing (tenant ID from webhook/API key)
5. **Phase 5:** Per-tenant billing and usage tracking

### Backward Compatibility

- All existing code continues to work unchanged
- Default tenant config = existing environment variable behavior
- No breaking changes to any existing interface
- SPI module is additive — existing modules don't need to implement it yet

## Files Changed

### New Module: `spi`
- `AiDrivenModule.java` — Core SPI interface
- `ModuleType.java` — Module category enum
- `ModuleContext.java` — Tenant-scoped config interface
- `ModuleRegistry.java` — ServiceLoader-based registry
- `HealthCheckResult.java` — Health check model
- `ModuleInitializationException.java` — Init failure exception
- `tenant/TenantConfig.java` — Immutable tenant configuration
- `tenant/DefaultModuleContext.java` — Default ModuleContext implementation

### Modified: `core`
- `TenantAwareAppConfig.java` — Tenant overlay on AppConfig
- `TenantConfigLoader.java` — Config loading and caching
- `build.gradle` — Added `api project(':spi')` dependency

### Modified: `settings.gradle`
- Added `include 'spi'` module
