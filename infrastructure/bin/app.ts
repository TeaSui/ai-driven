#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { TenantStack } from '../lib/tenant-stack';
import { TENANTS } from '../lib/config/tenants';

/**
 * Multi-tenant CDK application.
 *
 * Deploys an isolated stack per tenant, each with its own:
 *   - Shared infrastructure (DynamoDB, S3, Secrets, SQS)
 *   - Pipeline service (Step Functions workflow) — if enabled
 *   - Agent service (SQS-driven AI agent) — if enabled
 *   - API Gateway with tenant-specific endpoints
 *
 * Deployment:
 *   cdk deploy --all                              # Deploy all tenants
 *   cdk deploy AiDriven-default-Stack             # Deploy single tenant
 *   cdk deploy AiDriven-acme-corp-Stack           # Deploy specific tenant
 */
const app = new cdk.App();

const env = {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: process.env.CDK_DEFAULT_REGION || 'us-east-1',
};

for (const tenantConfig of TENANTS) {
    new TenantStack(app, `AiDriven-${tenantConfig.tenantId}-Stack`, {
        env,
        description: `AI-Driven Workflow Automation - Tenant: ${tenantConfig.tenantId}`,
        tenantConfig,
    });
}
