# impl-16: Flexible Multi-Tenant Architecture

## Status: Implemented

## Summary

Adds a flexible, modular multi-tenant architecture to support multiple companies with diverse workflow automation needs. Maintains the current Gradle modular monolith while enabling tenant-specific customization.

## Architecture

### Core Components

```
core/tenant/
  TenantConfig          — Per-tenant configuration (platform, secrets, plugins, limits)
  TenantRegistry        — In-memory registry of all tenants
  TenantContext         — Thread-local current tenant (set per Lambda invocation)
  TenantResolver        — Resolves tenant from incoming webhook/event
  TenantConfigLoader    — Loads configs from DynamoDB or environment variables

core/plugin/
  WorkflowPlugin        — Contract for extensible workflow plugins
  PluginRegistry        — Global plugin registry with per-tenant activation
```

### Multi-Tenancy Strategy

**Phase 1 (Current): Modular Monolith**
- Single Lambda deployment per tenant (separate CDK stacks)
- Tenant config loaded from environment variables
- Plugin activation via `TenantConfig.enabledPlugins`

**Phase 2: Shared Infrastructure**
- Single Lambda deployment, multiple tenants
- Tenant resolved from webhook headers/path
- Configs stored in DynamoDB (`TENANT#{id}` / `CONFIG`)
- Infrastructure isolation via separate DynamoDB tables and S3 buckets per tenant

**Phase 3: Full Multi-Tenancy**
- Tenant-aware routing at API Gateway level
- Per-tenant SQS queues for agent tasks
- Cost tracking per tenant

### Tenant Configuration

Each `TenantConfig` contains:
- Source control platform + credentials (Bitbucket/GitHub)
- Issue tracker credentials (Jira)
- AI model credentials (Claude)
- AWS resource ARNs (DynamoDB, S3, SFN, SQS)
- Plugin enablement flags
- Agent behavior settings (token budget, max turns, guardrails)
- Custom metadata (e.g., Jira project key mapping)

### Plugin System

Plugins extend the system for domain-specific capabilities:

```java
public interface WorkflowPlugin {
    String pluginId();                          // Unique ID
    boolean isEnabled(TenantConfig config);     // Tenant-specific activation
    void initialize(TenantConfig config);       // Setup per tenant
    Class<?> getToolProviderClass();            // Optional ToolProvider
}
```

Built-in plugins (always available):
- `source_control` — Bitbucket/GitHub operations
- `issue_tracker` — Jira operations  
- `code_context` — Repository context building

Optional plugins (tenant-configured):
- `monitoring` — CloudWatch/Datadog via MCP
- `messaging` — Slack/Teams via MCP
- `data` — Database queries via MCP

### DynamoDB Schema

```
PK: TENANT#{tenantId}
SK: CONFIG
Attributes: tenantName, active, defaultPlatform, jiraSecretArn,
            bitbucketSecretArn, githubSecretArn, claudeSecretArn,
            dynamoDbTableName, codeContextBucket, stateMachineArn,
            agentQueueUrl, triggerLabel, agentTriggerPrefix,
            agentTokenBudget, agentMaxTurns, guardrailsEnabled,
            enabledPlugins (SS), metadata (M), mcpServersConfig
```

## Usage

### Single-Tenant (Current)

```java
// Load from environment variables (existing behavior)
TenantConfig tenant = TenantConfigLoader.fromEnvironment("my-company");
TenantContext.set(tenant);
try {
    // ... process webhook
} finally {
    TenantContext.clear();
}
```

### Multi-Tenant (Phase 2)

```java
// Load all tenants at cold start
TenantConfigLoader loader = new TenantConfigLoader(dynamoDbClient, tableName);
loader.loadAll().forEach(registry::register);

// Resolve tenant per request
TenantResolver resolver = new TenantResolver(registry, defaultTenantId);
TenantConfig tenant = resolver.resolve(event);
TenantContext.set(tenant);
```

## Acceptance Criteria Coverage

- ✅ Flexible, modular architecture for workflow automation
- ✅ Supports multiple companies with diverse processes (TenantConfig)
- ✅ Maintains Gradle modular monolith (no new modules added)
- ✅ Avoids full microservices initially (single Lambda per tenant)
- ✅ Phased approach (env vars → DynamoDB → full multi-tenant)
- ✅ Tenant-aware configurations (TenantConfig + TenantConfigLoader)
- ✅ Plugin registration (PluginRegistry + WorkflowPlugin)
- ✅ Infrastructure separation (per-tenant ARNs in TenantConfig)
- ✅ Plugin extensibility (WorkflowPlugin interface)
