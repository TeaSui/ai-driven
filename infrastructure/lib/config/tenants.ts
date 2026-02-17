import { TenantConfig } from './tenant-config';

/**
 * Tenant registry — defines all client deployments.
 *
 * To onboard a new client:
 *   1. Add a new TenantConfig entry below
 *   2. Run `cdk deploy AiDriven-<tenantId>-Stack`
 *   3. Configure their Jira/source control secrets in AWS Secrets Manager
 *
 * Each tenant is fully isolated with its own:
 *   - AWS resources (DynamoDB, S3, SQS, Lambda, Step Functions)
 *   - API Gateway endpoint
 *   - Secrets and credentials
 *   - Configuration and limits
 */
export const TENANTS: TenantConfig[] = [
    // ==================== DEFAULT (original deployment) ====================
    TenantConfig.create('default', {
        enablePipelineMode: true,
        enableAgentMode: true,
        sourceControlPlatforms: ['GITHUB'],
        defaultPlatform: 'GITHUB',
        defaultWorkspace: 'TeaSui',
        defaultRepo: 'ai-driven',
        claudeModel: 'claude-sonnet-4-20250514',
    }),

    // ==================== EXAMPLE TENANTS (commented out) ====================
    // Uncomment and customize when onboarding new clients.

    // TenantConfig.create('acme-corp', {
    //     enablePipelineMode: true,
    //     enableAgentMode: true,
    //     sourceControlPlatforms: ['GITHUB'],
    //     defaultPlatform: 'GITHUB',
    //     defaultWorkspace: 'acme-corp',
    //     defaultRepo: 'main-app',
    //     claudeModel: 'claude-sonnet-4-20250514',
    //     agentCostBudgetPerTicket: 100000,
    //     limits: {
    //         maxContextForClaude: 500000,
    //     },
    // }),

    // TenantConfig.create('startup-xyz', {
    //     enablePipelineMode: false,  // Agent-only plan
    //     enableAgentMode: true,
    //     sourceControlPlatforms: ['BITBUCKET'],
    //     defaultPlatform: 'BITBUCKET',
    //     defaultWorkspace: 'startup-xyz',
    //     defaultRepo: 'backend',
    //     claudeModel: 'claude-sonnet-4-20250514',
    //     agentMaxTurns: 5,  // Lower limits for starter plan
    //     agentCostBudgetPerTicket: 50000,
    // }),
];
