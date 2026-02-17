# impl-16: Microservices Adaptation

**Ticket:** CRM-88  
**Status:** Implemented  
**Author:** AI Agent  

---

## Goal

Transform the monolithic deployment into a modular, multi-tenant SaaS architecture so the workflow automation service can be offered to multiple companies on a subscription basis. Each client has different business processes, tools, and integration requirements.

## Problem Statement

The current architecture uses a single monolithic fat JAR (`lambda-handlers-all.jar`) deployed to all Lambda functions, and a single CDK stack (`AiDrivenStack`) that provisions all resources. This creates several issues for multi-tenant SaaS:

1. **No tenant isolation** - all clients would share the same DynamoDB table, S3 bucket, and secrets
2. **All-or-nothing deployment** - can't deploy only the pipeline or only the agent for a client
3. **No per-client configuration** - limits, models, and platform choices are global
4. **Blast radius** - a deployment failure affects all clients simultaneously
5. **Cold start penalty** - every Lambda loads all dependencies even if it only needs a subset

## Decision: Modular Monolith (Not Full Microservices)

We chose a **modular monolith** approach over full microservices for these reasons:

| Approach | Pros | Cons |
|----------|------|------|
| Full microservices (separate repos) | Maximum isolation | Massive operational overhead, cross-repo dependency management, distributed debugging |
| Full microservices (mono-repo) | Good isolation | Complex CI/CD, service mesh needed, eventual consistency challenges |
| **Modular monolith (chosen)** | Independent deployment per service, shared code via Gradle modules, simple CI/CD | Shared build system (acceptable trade-off) |
| Status quo (monolith) | Simple | No tenant isolation, no selective deployment |

The modular monolith gives us 80% of microservices benefits with 20% of the complexity.

## Architecture

### Gradle Module Structure

```
application/
+-- core/                    # Shared interfaces, models, config
+-- jira-client/             # Jira API client
+-- bitbucket-client/        # Bitbucket API client
+-- github-client/           # GitHub API client
+-- claude-client/           # Claude AI client
+-- tool-source-control/     # Agent tool: source control
+-- tool-issue-tracker/      # Agent tool: issue tracker
+-- tool-code-context/       # Agent tool: code context
+-- mcp-bridge/              # MCP external integrations
+-- pipeline-handlers/       # NEW: Pipeline service JAR
|   +-- PipelineServiceFactory (slim: no SQS, no agent deps)
+-- agent-handlers/          # NEW: Agent service JAR
|   +-- AgentServiceFactory (slim: no SfnClient, no Step Functions)
+-- lambda-handlers/         # Legacy monolithic JAR (backward compat)
```

### CDK Construct Structure

```
infrastructure/
+-- bin/app.ts                              # Multi-tenant entry point
+-- lib/
    +-- config/
    |   +-- tenant-config.ts                # Per-tenant configuration
    |   +-- tenants.ts                      # Tenant registry
    +-- constructs/
    |   +-- shared-infra-construct.ts       # DynamoDB, S3, Secrets, SQS
    |   +-- pipeline-service-construct.ts   # Step Functions + handlers
    |   +-- agent-service-construct.ts      # SQS + agent handlers
    +-- tenant-stack.ts                     # Composes constructs per tenant
    +-- ai-driven-stack.ts                  # Legacy (retained)
```

### Per-Tenant Isolation

Each tenant gets completely separate AWS resources:
- `ai-driven-{tenantId}-state` (DynamoDB)
- `ai-driven-{tenantId}-code-context-{accountId}` (S3)
- `ai-driven-{tenantId}/claude-api-key` (Secrets)
- `ai-driven-{tenantId}-jira-webhook` (Lambda)
- `ai-driven-{tenantId}-linear-workflow` (Step Functions)

### Conditional Service Deployment

TenantConfig controls which services are deployed:
```typescript
TenantConfig.create('startup-xyz', {
    enablePipelineMode: false,  // Agent-only plan
    enableAgentMode: true,
    sourceControlPlatforms: ['BITBUCKET'],  // Only Bitbucket secrets created
});
```

### Split Service Factories

| Factory | Module | Dependencies | Excludes |
|---------|--------|-------------|----------|
| PipelineServiceFactory | pipeline-handlers | SfnClient, S3, DynamoDB | SQS, ToolRegistry, MCP, CostTracker |
| AgentServiceFactory | agent-handlers | SQS, ToolRegistry, MCP, CostTracker | SfnClient, Step Functions |
| ServiceFactory (legacy) | lambda-handlers | Everything | Nothing |

## Onboarding a New Client

1. Add a `TenantConfig.create(...)` entry in `infrastructure/lib/config/tenants.ts`
2. Run `cdk deploy AiDriven-{tenantId}-Stack`
3. Populate secrets in AWS Secrets Manager
4. Configure the client's Jira webhook to point to the tenant's API Gateway URL
5. Done - fully isolated deployment

## Migration Path

The original `AiDrivenStack` and `lambda-handlers` module are retained for backward compatibility. Migration steps:

1. Deploy the new `AiDriven-default-Stack` alongside the existing stack
2. Update Jira webhooks to point to the new API Gateway
3. Verify both pipeline and agent modes work
4. Decommission the old `AiDrivenStack`
5. Remove `ai-driven-stack.ts` and `lambda-handlers/` module

## Future Enhancements

- **Tenant management API** - REST API to CRUD tenant configs without CDK redeployment
- **Shared resource pool** - optional shared DynamoDB table with tenant-prefixed keys for cost optimization
- **Usage metering** - per-tenant token consumption tracking for billing
- **Tenant-specific MCP servers** - each client can bring their own monitoring/messaging tools
- **Cross-account deployment** - deploy tenant stacks to separate AWS accounts for maximum isolation
