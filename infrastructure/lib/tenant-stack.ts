import * as cdk from 'aws-cdk-lib';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import { Construct } from 'constructs';
import { SharedInfraConstruct } from './constructs/shared-infra-construct';
import { PipelineServiceConstruct } from './constructs/pipeline-service-construct';
import { AgentServiceConstruct } from './constructs/agent-service-construct';
import { TenantConfig } from './config/tenant-config';

/**
 * Per-tenant stack that composes the appropriate service constructs
 * based on the tenant's configuration.
 *
 * Each tenant gets:
 *   - Shared infrastructure (always)
 *   - Pipeline service (if enablePipelineMode)
 *   - Agent service (if enableAgentMode)
 *   - A single API Gateway with routes for enabled services
 *
 * This enables true multi-tenant isolation:
 *   - Separate DynamoDB tables per tenant
 *   - Separate S3 buckets per tenant
 *   - Separate secrets per tenant
 *   - Separate Lambda functions per tenant
 *   - Independent deployment lifecycle per tenant
 */
export interface TenantStackProps extends cdk.StackProps {
    readonly tenantConfig: TenantConfig;
}

export class TenantStack extends cdk.Stack {
    constructor(scope: Construct, id: string, props: TenantStackProps) {
        super(scope, id, props);

        const { tenantConfig } = props;

        // ==================== SHARED INFRASTRUCTURE ====================
        const sharedInfra = new SharedInfraConstruct(this, 'SharedInfra', {
            tenantConfig,
        });

        // ==================== API GATEWAY ====================
        const api = new apigateway.RestApi(this, 'WebhookApi', {
            restApiName: `${tenantConfig.resourcePrefix}-webhook`,
            description: `Webhook endpoints for tenant: ${tenantConfig.tenantId}`,
            deployOptions: {
                stageName: 'prod',
                throttlingRateLimit: 100,
                throttlingBurstLimit: 200,
            },
        });

        // ==================== PIPELINE SERVICE (optional) ====================
        if (tenantConfig.enablePipelineMode) {
            new PipelineServiceConstruct(this, 'PipelineService', {
                tenantConfig,
                sharedInfra,
                api,
            });
        }

        // ==================== AGENT SERVICE (optional) ====================
        if (tenantConfig.enableAgentMode) {
            new AgentServiceConstruct(this, 'AgentService', {
                tenantConfig,
                sharedInfra,
                api,
            });
        }

        // ==================== OUTPUTS ====================
        new cdk.CfnOutput(this, 'ApiUrl', {
            value: api.url,
            description: `Webhook API URL for tenant: ${tenantConfig.tenantId}`,
        });

        new cdk.CfnOutput(this, 'TenantId', {
            value: tenantConfig.tenantId,
            description: 'Tenant identifier',
        });

        new cdk.CfnOutput(this, 'EnabledServices', {
            value: [
                tenantConfig.enablePipelineMode ? 'pipeline' : null,
                tenantConfig.enableAgentMode ? 'agent' : null,
            ].filter(Boolean).join(', '),
            description: 'Enabled services for this tenant',
        });
    }
}
