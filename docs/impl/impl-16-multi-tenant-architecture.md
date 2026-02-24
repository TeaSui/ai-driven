# impl-16: Flexible, Modular Multi-Tenant Architecture

## Status: Implemented

## Summary

Adds a flexible, modular multi-tenant architecture to the workflow automation system. Each company (tenant) can have its own configuration, enabled plugins, and workflow customizations — without code duplication or microservice extraction.

## Architecture

### Core Components

```
core/
  tenant/
    TenantConfig          — Per-tenant configuration (platform, plugins, model, etc.)
    TenantRegistry        — In-memory registry of all tenants
    TenantContext         — Thread-local current tenant (request-scoped)
    TenantResolver        — Resolves tenant from ticket labels/project keys
    DynamoTenantRepository — DynamoDB persistence for tenant configs
  plugin/
    WorkflowPlugin        — Plugin contract (pluginId, isApplicable, initialize)
    PluginRegistry        — Global plugin registry with per-tenant activation
```

### Tenant Resolution Priority

1. Explicit Jira label: `tenant:acme-corp`
2. Jira project key mapping (e.g., `ACME` → `acme-corp` tenant)
3. Default tenant fallback (configured via env var)

### Plugin Activation

Plugins are registered globally but activated per-tenant:

```java
// Register globally (once, at startup)
pluginRegistry.register(new SlackNotificationPlugin());
pluginRegistry.register(new SonarQubePlugin());

// Activate per-tenant (via TenantConfig)
TenantConfig acme = TenantConfig.builder()
    .tenantId("acme")
    .enabledPlugins(Set.of("slack-notifications", "sonarqube"))
    .pluginConfig(Map.of(
        "slack-notifications", Map.of("channel", "#dev-alerts")
    ))
    .build();
```

### DynamoDB Schema

Tenant configs use the existing single-table design:
- PK: `TENANT#{tenantId}`
- SK: `CONFIG`

### Phased Evolution

**Phase 1 (Current):** Modular monolith with tenant-aware config
- Single Lambda deployment
- Per-tenant config in DynamoDB
- Plugin registry with tenant-level enable/disable

**Phase 2 (Future):** Multi-tenant CDK stacks
- Separate Step Functions per tenant
- Tenant-isolated DynamoDB tables
- Per-tenant secrets

**Phase 3 (Future):** Selective microservice extraction
- Extract only when specific scaling needs arise
- Maintain shared plugin registry

## Usage

### Registering a Tenant

```java
TenantConfig config = TenantConfig.builder()
    .tenantId("acme-corp")
    .tenantName("Acme Corporation")
    .defaultPlatform("GITHUB")
    .defaultWorkspace("acme-org")
    .jiraProjectKeys(List.of("ACME", "BACKEND"))
    .enabledPlugins(Set.of("slack-notifications"))
    .claudeModelOverride("claude-sonnet-4-5") // cost optimization
    .branchPrefix("acme/ai/")
    .build();

tenantRegistry.register(config);
```

### Resolving Tenant in a Handler

```java
TicketInfo ticket = jiraClient.getTicket(ticketKey);
Optional<TenantConfig> tenant = tenantResolver.resolve(ticket);
tenant.ifPresent(TenantContext::set);
try {
    // process with tenant context available
} finally {
    TenantContext.clear();
}
```

### Writing a Plugin

```java
public class SlackNotificationPlugin implements WorkflowPlugin {
    public String pluginId() { return "slack-notifications"; }
    public String displayName() { return "Slack Notifications"; }
    
    public boolean isApplicable(TenantConfig tenant) {
        return tenant.getPluginConfig("slack-notifications").containsKey("channel");
    }
    
    public void initialize(TenantConfig tenant, Map<String, String> conf) {
        this.channel = conf.get("channel");
        this.token = conf.get("token");
    }
    
    public void notifyPrCreated(String prUrl, String ticketKey) {
        // post to Slack
    }
}
```
