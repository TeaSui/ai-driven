# impl-16: Flexible, Modular Multi-Tenant Architecture

## Status: Implemented

## Summary

Adds a tenant-aware, plugin-based architecture to support multiple companies with diverse workflow automation needs, while maintaining the current Gradle modular monolith structure.

## New Components

### `core/tenant/`

| Class | Purpose |
|-------|---------|
| `TenantConfig` | Per-tenant configuration (platform, secrets, plugins, limits) |
| `TenantRegistry` | Thread-safe registry of all tenant configurations |
| `TenantResolver` | Resolves tenant from ticket labels, project key, or default |
| `TenantContextHolder` | Thread-local tenant context for the current request |
| `TenantConfigLoader` | Loads tenant configs from env var or Secrets Manager |

### `core/plugin/`

| Class | Purpose |
|-------|---------|
| `WorkflowPlugin` | Contract for workflow automation plugins |
| `PluginRegistry` | Global registry; activates plugins per-tenant |

## Architecture

```
Jira Webhook
    │
    ▼
TenantResolver.resolve(ticket)
    │  1. tenant: label
    │  2. project key
    │  3. default tenant
    ▼
TenantConfig (per-tenant settings)
    │
    ├── platform (GITHUB/BITBUCKET)
    ├── secrets (source control, Jira, AI)
    ├── enabledPlugins → PluginRegistry.getEnabledPlugins(tenant)
    ├── agentConfig (maxTurns, tokenBudget, guardrails)
    └── branchPrefix, triggerLabel, claudeModel
```

## Tenant Configuration

Tenants are configured via the `TENANT_CONFIGS` environment variable (JSON array) or `TENANT_CONFIG_SECRET_ARN` (Secrets Manager):

```json
[
  {
    "tenantId": "acme-corp",
    "tenantName": "Acme Corporation",
    "platform": "GITHUB",
    "sourceControlSecretArn": "arn:aws:secretsmanager:...:acme-github",
    "issueTrackerSecretArn": "arn:aws:secretsmanager:...:acme-jira",
    "aiSecretArn": "arn:aws:secretsmanager:...:acme-claude",
    "defaultRepoOwner": "acme-org",
    "defaultRepo": "backend-service",
    "enabledPlugins": ["monitoring", "messaging"],
    "agentEnabled": true,
    "guardrailsEnabled": true,
    "tokenBudgetPerTicket": 300000,
    "claudeModel": "claude-sonnet-4-5",
    "branchPrefix": "ai/",
    "triggerLabel": "ai-generate",
    "active": true
  }
]
```

## Plugin System

Plugins implement `WorkflowPlugin` and are registered globally. They are activated per-tenant based on `enabledPlugins`:

```java
// Register globally (once at startup)
pluginRegistry.register(new MonitoringPlugin());
pluginRegistry.register(new MessagingPlugin());

// Activate per-tenant
List<WorkflowPlugin> active = pluginRegistry.getEnabledPlugins(tenantConfig);
```

## Phased Evolution

| Phase | Description |
|-------|-------------|
| **Current** | Modular monolith with tenant-aware config |
| **Phase 2** | Per-tenant DynamoDB table prefix / namespace isolation |
| **Phase 3** | Per-tenant SQS queues for agent processing |
| **Phase 4** | Extract high-scale tenants to dedicated Lambda functions |
| **Phase 5** | Full microservices for tenants requiring independent scaling |

## Jira Labels

| Label | Effect |
|-------|--------|
| `tenant:acme-corp` | Route to specific tenant config |
| `tenant:startup-xyz` | Route to another tenant |

## Infrastructure Isolation

The CDK stack (`ai-driven-stack.ts`) can be parameterized per tenant:
- Separate DynamoDB table prefixes
- Separate S3 buckets for code context
- Separate SQS queues for agent processing
- Shared Lambda functions (cost-efficient for early stage)
