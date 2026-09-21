# Greenfield Service Checklist

## Sequence (stack-agnostic)

1. **Project skeleton** — source structure, config, dependency manifest
2. **Deploy "hello world" to production** — prove the pipeline works FIRST
3. **CI/CD** — test → build → deploy, automated on push
4. **Observability** — structured logging, error alerting, basic dashboard
5. **Data store** — primary table/schema, migrations setup, backup enabled
6. **One integration test** — hit deployed endpoint, verify response
7. **API contract** — OpenAPI stub for first endpoint in module README

Then proceed with normal feature development workflow.

## Current Default Stack

- **IaC:** AWS CDK (TypeScript), region ap-southeast-1
- **Compute:** Lambda (Node.js 22.x, ARM64, 512MB)
- **Data:** DynamoDB (single-table, PAY_PER_REQUEST, PITR, RETAIN)
- **CI/CD:** GitHub Actions
- **Observability:** Lambda PowerTools, CloudWatch, X-Ray
- **Profile:** AWS_PROFILE=ai-driven
