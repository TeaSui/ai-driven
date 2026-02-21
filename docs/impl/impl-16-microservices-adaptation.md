# impl-16: Microservices Adaptation for Multi-Tenant SaaS

## Overview

This document describes the architectural adaptation of the AI-Driven workflow automation platform to support a **multi-tenant SaaS model**, where each client (company) can subscribe to specific modules and configure them independently.

## Current Architecture Assessment

The project already has excellent module separation:

```
application/
  core/              # Shared interfaces, models, config
  jira-client/       # Issue tracker implementation
  bitbucket-client/  # Source control implementation
  github-client/     # Source control implementation
  claude-client/     # AI engine implementation
  lambda-handlers/   # Orchestration layer
  mcp-bridge/        # External tool integration
  tool-*/            # Tool provider implementations
```

This is already a **modular monolith** — the foundation for microservices is in place.

## Proposed Multi-Tenant Architecture

### Tier 1: Core Platform (Always Deployed)
- `core` — Interfaces, models, tenant config
- `lambda-handlers` — Orchestration
- `claude-client` — AI engine

### Tier 2: Issue Tracker Adapters (Client Chooses One or More)
- `jira-client` — Jira Cloud
- `linear-client` (future) — Linear
- `github-issues-client` (future) — GitHub Issues

### Tier 3: Source Control Adapters (Client Chooses One or More)
- `bitbucket-client` — Bitbucket Cloud
- `github-client` — GitHub
- `gitlab-client` (future) — GitLab

### Tier 4: Optional Tool Modules (Client Opts In)
- `tool-source-control` — Code operations
- `tool-issue-tracker` — Ticket management
- `tool-code-context` — Code analysis
- `mcp-bridge` — External MCP servers (monitoring, messaging, data)

## Tenant Configuration Model

Each tenant is identified by a `tenantId` and has a configuration stored in DynamoDB:

```json
{
  "tenantId": "acme-corp",
  "displayName": "ACME Corporation",
  "plan": "ENTERPRISE",
  "enabledModules": ["source_control", "issue_tracker", "code_context"],
  "issueTracker": {
    "type": "JIRA",
    "secretArn": "arn:aws:secretsmanager:...:acme-jira"
  },
  "sourceControl": {
    "type": "GITHUB",
    "secretArn": "arn:aws:secretsmanager:...:acme-github"
  },
  "aiEngine": {
    "model": "claude-opus-4-6",
    "maxTokensPerMonth": 10000000
  },
  "mcpServers": [
    {
      "namespace": "monitoring",
      "transport": "http",
      "url": "https://mcp.datadog.com",
      "secretArn": "arn:aws:secretsmanager:...:acme-datadog",
      "enabled": true
    }
  ]
}
```

## Implementation Plan

### Phase 1: Tenant Configuration (This PR)
- `TenantConfig` model
- `TenantConfigRepository` (DynamoDB)
- `TenantContextResolver` — resolves tenant from webhook
- `TenantAwareServiceFactory` — creates per-tenant clients

### Phase 2: Billing & Usage Tracking
- Token usage per tenant
- Monthly quota enforcement
- Usage reports

### Phase 3: Tenant Onboarding API
- REST API for tenant registration
- Secret provisioning automation
- Module activation/deactivation

### Phase 4: True Microservices (Optional)
- Extract each module as an independent Lambda function set
- Per-tenant Step Functions state machines
- Tenant-isolated DynamoDB tables (or table-per-tenant prefix)

## Counter-Arguments & Trade-offs

### Argument FOR Microservices
- Independent deployment per module
- Client-specific scaling
- Fault isolation

### Argument FOR Modular Monolith (Current Approach)
- Lower operational complexity
- Simpler debugging
- Lambda cold starts are already isolated per function
- The current multi-module Gradle structure already provides build isolation
- **Recommendation**: Stay modular monolith, add tenant configuration layer

## Recommended Approach

The current architecture is already well-suited for multi-tenant SaaS. The key additions needed are:

1. **Tenant identification** in webhook handlers
2. **Per-tenant configuration** stored in DynamoDB
3. **Dynamic client instantiation** based on tenant config
4. **Usage tracking** per tenant
5. **Tenant isolation** in DynamoDB (key prefix: `TENANT#{tenantId}#...`)
