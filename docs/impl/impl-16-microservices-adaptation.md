# impl-16: Microservices Adaptation

**Ticket:** CRM-88  
**Status:** In Progress  
**Priority:** Medium

## Goal

Refactor the project architecture to support multi-tenant SaaS deployment where each client company can use different tools and integrations.

## Problem

The current architecture, while modular (Gradle multi-module), has tight coupling between:
- Lambda handlers and specific AWS services (DynamoDB, S3, SQS, Step Functions)
- Core logic and specific platform clients (Bitbucket, GitHub, Jira)
- Configuration and AWS Secrets Manager

This makes it difficult to:
1. Offer the system to companies using different tools (GitLab, Linear, Azure DevOps)
2. Deploy on different cloud providers (Azure, GCP)
3. Customize behavior per tenant without code changes

## Solution: Service Provider Interface (SPI) Layer

### Architecture

```
┌─────────────────────────────────────────────────────┐
│                   lambda-handlers                    │
│              (or Spring Boot, Micronaut)             │
├─────────────────────────────────────────────────────┤
│                      core                            │
│         (business logic, agent orchestrator)          │
├─────────────────────────────────────────────────────┤
│                      spi                             │
│    (provider interfaces, tenant context, registry)   │
├──────────┬──────────┬──────────┬────────────────────┤
│ jira     │ bitbucket│ github   │ claude   │ ...     │
│ client   │ client   │ client   │ client   │         │
│ (impl)   │ (impl)   │ (impl)   │ (impl)   │         │
└──────────┴──────────┴──────────┴──────────┴─────────┘
```

### New Module: `spi`

Contains only Java interfaces and records — zero implementation dependencies:

| Interface | Purpose | Example Implementations |
|-----------|---------|------------------------|
| `SourceControlProvider` | Git operations | Bitbucket, GitHub, GitLab, Azure DevOps |
| `IssueTrackerProvider` | Ticket management | Jira, Linear, Notion, Shortcut |
| `AiModelProvider` | LLM interactions | Claude, OpenAI, Bedrock, Ollama |
| `SecretsProvider` | Credentials | AWS SM, Vault, Azure KV |
| `StorageProvider` | Object storage | S3, Azure Blob, GCS |
| `StateStoreProvider` | Key-value state | DynamoDB, Redis, PostgreSQL |
| `WorkflowProvider` | Orchestration | Step Functions, Temporal |
| `QueueProvider` | Messaging | SQS, RabbitMQ, Kafka |
| `NotificationProvider` | Notifications | Slack, Teams, Email |

### Multi-Tenant Resolution

```java
// Register providers
ProviderRegistry registry = new ProviderRegistry();
registry.register(SourceControlProvider.class, "github", githubProvider);
registry.register(SourceControlProvider.class, "bitbucket", bitbucketProvider);

// Bind tenants
registry.bindTenant("acme-corp", SourceControlProvider.class, "github");
registry.bindTenant("globex", SourceControlProvider.class, "bitbucket");

// Resolve at runtime
SourceControlProvider scm = registry.resolve(SourceControlProvider.class, tenantContext);
```

### Module Independence

Each module can now be:
- **Built independently**: `./gradlew :jira-client:build`
- **Published as artifact**: Maven publishing enabled per module
- **Tested in isolation**: No cross-module test dependencies
- **Deployed selectively**: Only include modules needed per tenant

## Migration Path

### Phase 1 (This PR) ✅
- [x] Create `spi` module with all provider interfaces
- [x] Add `TenantContext` for multi-tenant support
- [x] Add `ProviderRegistry` for runtime resolution
- [x] Add `ModuleDescriptor` for self-describing modules
- [x] Wire `spi` as dependency of `core`
- [x] Enable Maven publishing for all modules

### Phase 2 (Next)
- [ ] Create adapter classes in each client module implementing SPI interfaces
- [ ] Refactor `ServiceFactory` to use `ProviderRegistry`
- [ ] Add tenant resolution to webhook handlers

### Phase 3 (Future)
- [ ] Add `ModuleLoader` with ServiceLoader discovery
- [ ] Create tenant management API
- [ ] Add per-tenant configuration storage
- [ ] Support hot-loading of modules

## Backward Compatibility

All existing interfaces (`SourceControlClient`, `IssueTrackerClient`, `AiClient`) remain unchanged. The SPI layer is additive — existing code continues to work. Migration to SPI-based resolution is opt-in per handler.
