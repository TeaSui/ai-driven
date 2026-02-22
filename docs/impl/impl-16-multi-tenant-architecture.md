# impl-16: Flexible, Modular Multi-Tenant Architecture

## Summary

This implementation introduces a flexible, modular multi-tenant architecture for the workflow automation platform. It enables the system to serve multiple companies (tenants) with diverse processes while maintaining a single deployable codebase (modular monolith).

## Architecture Overview

```
core/
  tenant/
    TenantConfig          — Per-tenant settings (secrets, platform, tools, limits)
    TenantRegistry        — Thread-safe registry of all tenant configurations
    TenantResolver        — Resolves tenant from ticket labels / project key
    TenantConfigLoader    — Loads configs from env vars (single or multi-tenant)
  plugin/
    WorkflowPlugin        — Extension point interface for tenant customizations
    WorkflowContext       — Immutable context passed through the pipeline
    PluginRegistry        — Registry for global and per-tenant plugins
```

## Key Design Decisions

### 1. Modular Monolith (Not Microservices)
The architecture remains a single deployable JAR. Tenants are isolated at the configuration and plugin level, not at the service level. This avoids operational complexity while enabling customization.

### 2. Tenant Resolution Priority
1. Jira label: `tenant:acme` (explicit)
2. Project key: `ACME-123` → `acme` (implicit)
3. Default tenant from `DEFAULT_TENANT_ID` env var

### 3. Plugin System
Tenants can register `WorkflowPlugin` implementations that hook into key lifecycle events:
- `onTicketReceived` — enrich/validate ticket data
- `onBeforeCodeGeneration` — inject additional context
- `onAfterCodeGeneration` — validate/post-process generated files
- `onPrCreated` — send notifications, update external systems

### 4. Configuration Loading

**Single-tenant mode** (backward compatible): reads existing env vars (`JIRA_SECRET_ARN`, etc.)

**Multi-tenant mode**: reads `TENANTS_CONFIG` env var (JSON array):
```json
[
  {
    "tenantId": "acme",
    "tenantName": "Acme Corp",
    "jiraSecretArn": "arn:aws:...",
    "bitbucketSecretArn": "arn:aws:...",
    "defaultPlatform": "BITBUCKET",
    "enabledTools": ["monitoring"],
    "triggerLabels": ["ai-generate"]
  }
]
```

## Phased Evolution

| Phase | Description |
|-------|-------------|
| **Phase 1 (current)** | Single-tenant, modular monolith |
| **Phase 2 (this PR)** | Multi-tenant config + plugin system |
| **Phase 3 (future)** | Per-tenant CDK stacks, DynamoDB table isolation |
| **Phase 4 (future)** | Extract microservices for high-scale tenants |

## Infrastructure Separation

Tenant isolation is achieved without code duplication:
- Each tenant has its own AWS Secrets Manager secrets
- CDK stack can deploy per-tenant Lambda configurations via env vars
- DynamoDB uses `TENANT#{tenantId}#TICKET#{ticketId}` key prefix (future)

## Acceptance Criteria Status

- ✅ Flexible, modular architecture for workflow automation
- ✅ Supports multiple companies with diverse processes
- ✅ Maintains current modularized Gradle codebase
- ✅ Avoids full microservices architecture
- ✅ Phased approach: modular monolith → multi-tenant
- ✅ Tenant-aware configurations (TenantConfig, TenantRegistry)
- ✅ Plugin registration (PluginRegistry, WorkflowPlugin)
- ✅ Infrastructure separation via per-tenant secrets
- ✅ Plugin extensibility for tenant customization
