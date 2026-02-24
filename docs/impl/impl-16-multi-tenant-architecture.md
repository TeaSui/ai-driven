# impl-16: Flexible, Modular Multi-Tenant Architecture

## Summary

This implementation adds a flexible, modular multi-tenant architecture to the workflow automation system. It enables the platform to serve multiple companies (tenants) with diverse processes while maintaining a single codebase.

## Architecture Overview

```
Workflow Request
      │
      ▼
MultiTenantWorkflowRouter
      │
      ├── Resolve tenant from label (tenant:acme-corp)
      ├── Resolve tenant from project key
      └── Fall back to default tenant
            │
            ▼
      TenantContext (thread-local)
            │
            ▼
      TenantConfig
      ├── Source control credentials
      ├── Issue tracker credentials
      ├── Enabled plugins
      ├── Feature flags
      ├── Agent configuration
      └── Workflow step overrides
```

## New Components

### `TenantConfig`
Per-tenant configuration containing all customizable settings:
- Source control and issue tracker credentials (per-tenant secret ARNs)
- Enabled plugin namespaces
- Feature flags
- Agent behavior overrides
- Custom workflow steps

### `TenantRegistry`
Thread-safe registry for tenant configurations. Supports:
- Register/deregister tenants
- Find by ID
- List all tenants

### `TenantContext`
Thread-local holder for the current tenant. Set at Lambda invocation start, cleared in finally block.

### `TenantConfigLoader`
Loads tenant configurations from DynamoDB using the existing single-table design:
- PK: `TENANT#{tenantId}`
- SK: `CONFIG`

### `MultiTenantWorkflowRouter`
Routes workflow requests to the correct tenant:
1. Explicit label: `tenant:acme-corp`
2. Project key mapping
3. Default tenant (single-tenant mode)
4. Single registered tenant (auto-resolve)

### `PluginDescriptor` + `PluginRegistry`
Plugin metadata registry. Plugins are registered globally but enabled per-tenant via `TenantConfig.enabledPlugins`.

## Tenant Onboarding

To onboard a new tenant:

1. Create AWS Secrets Manager secrets for their credentials
2. Create a `TenantConfig` record in DynamoDB
3. Add `tenant:{id}` label to their Jira tickets (or configure project key mapping)

```json
{
  "PK": "TENANT#acme-corp",
  "SK": "CONFIG",
  "tenantId": "acme-corp",
  "tenantName": "ACME Corporation",
  "jiraSecretArn": "arn:aws:secretsmanager:...:acme-jira",
  "bitbucketSecretArn": "arn:aws:secretsmanager:...:acme-bitbucket",
  "defaultPlatform": "GITHUB",
  "enabledPlugins": ["monitoring", "messaging"],
  "featureFlags": {"agent-mode": true}
}
```

## Phased Evolution

### Phase 1 (Current): Modular Monolith
- Single deployment, tenant config in DynamoDB
- Tenant isolation via configuration
- Shared infrastructure with logical separation

### Phase 2: Infrastructure Separation
- Per-tenant DynamoDB tables
- Per-tenant S3 buckets
- Tenant-specific Lambda configurations

### Phase 3: Multi-Tenant CDK
- CDK constructs per tenant
- Automated tenant onboarding pipeline
- Cost allocation per tenant

### Phase 4: Selective Microservices
- Extract services only when scaling needs arise
- Keep shared services as monolith

## Infrastructure Isolation

The `TenantConfig` supports per-tenant infrastructure:
- `dynamoDbTableName`: Tenant-specific DynamoDB table
- `codeContextBucket`: Tenant-specific S3 bucket
- `awsRegion`: Tenant-specific AWS region

This allows gradual migration from shared to isolated infrastructure.
