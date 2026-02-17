import * as cdk from 'aws-cdk-lib';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambdaEventSources from 'aws-cdk-lib/aws-lambda-event-sources';
import { Construct } from 'constructs';
import { SharedInfraConstruct } from './shared-infra-construct';
import { TenantConfig } from '../config/tenant-config';
import * as path from 'path';

/**
 * Agent Service: interactive, multi-turn AI agent.
 * Trigger: Jira comment "@ai ..." -> webhook -> SQS FIFO -> AgentProcessor.
 *
 * This construct is independently deployable. A tenant that only needs
 * the agent mode can deploy SharedInfra + AgentService.
 */
export interface AgentServiceProps {
    readonly tenantConfig: TenantConfig;
    readonly sharedInfra: SharedInfraConstruct;
    readonly api: apigateway.RestApi;
}

export class AgentServiceConstruct extends Construct {
    constructor(scope: Construct, id: string, props: AgentServiceProps) {
        super(scope, id);

        const { tenantConfig, sharedInfra, api } = props;
        const prefix = tenantConfig.resourcePrefix;

        if (!sharedInfra.agentQueue) {
            throw new Error('Agent mode is enabled but no SQS queue was created in SharedInfra. ' +
                'Ensure tenantConfig.enableAgentMode is true.');
        }

        const javaRuntime = lambda.Runtime.JAVA_21;
        const lambdaCodePath = path.join(__dirname, '../../../application/agent-handlers/build/libs/agent-handlers-all.jar');
        const lambdaCode = lambda.Code.fromAsset(lambdaCodePath);

        // ==================== ENVIRONMENT ====================
        const agentEnvironment: Record<string, string> = {
            TENANT_ID: tenantConfig.tenantId,
            DYNAMODB_TABLE_NAME: sharedInfra.stateTable.tableName,
            CLAUDE_SECRET_ARN: sharedInfra.claudeApiKeySecret.secretArn,
            JIRA_SECRET_ARN: sharedInfra.jiraSecret.secretArn,
            BITBUCKET_SECRET_ARN: sharedInfra.bitbucketSecret?.secretArn ?? '',
            GITHUB_SECRET_ARN: sharedInfra.githubSecret?.secretArn ?? '',
            CODE_CONTEXT_BUCKET: sharedInfra.codeContextBucket.bucketName,
            MAX_FILE_SIZE_CHARS: String(tenantConfig.limits.maxFileSizeChars),
            MAX_TOTAL_CONTEXT_CHARS: String(tenantConfig.limits.maxTotalContextChars),
            MAX_CONTEXT_FOR_CLAUDE: String(tenantConfig.limits.maxContextForClaude),
            CLAUDE_MODEL: tenantConfig.claudeModel,
            CLAUDE_MAX_TOKENS: String(tenantConfig.claudeMaxTokens),
            CLAUDE_TEMPERATURE: String(tenantConfig.claudeTemperature),
            CONTEXT_MODE: tenantConfig.contextMode,
            PROMPT_VERSION: tenantConfig.promptVersion,
            BRANCH_PREFIX: tenantConfig.branchPrefix,
            DEFAULT_PLATFORM: tenantConfig.defaultPlatform,
            DEFAULT_WORKSPACE: tenantConfig.defaultWorkspace,
            DEFAULT_REPO: tenantConfig.defaultRepo,
            // Agent-specific
            AGENT_ENABLED: 'true',
            AGENT_TRIGGER_PREFIX: tenantConfig.agentTriggerPrefix,
            AGENT_MAX_TOOL_TURNS: String(tenantConfig.agentMaxTurns),
            AGENT_MAX_WALL_CLOCK_SECONDS: String(tenantConfig.agentMaxWallClockSeconds),
            AGENT_QUEUE_URL: sharedInfra.agentQueue.queueUrl,
            AGENT_GUARDRAILS_ENABLED: String(tenantConfig.agentGuardrailsEnabled),
            AGENT_COST_BUDGET_PER_TICKET: String(tenantConfig.agentCostBudgetPerTicket),
            AGENT_CLASSIFIER_USE_LLM: String(tenantConfig.agentClassifierUseLlm),
            MCP_SERVERS_CONFIG: tenantConfig.mcpServersConfig,
        };

        // ==================== IAM ROLES ====================
        const agentWebhookRole = new iam.Role(this, 'AgentWebhookRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        sharedInfra.stateTable.grantReadWriteData(agentWebhookRole);
        sharedInfra.jiraSecret.grantRead(agentWebhookRole);
        sharedInfra.agentQueue.grantSendMessages(agentWebhookRole);

        const agentProcessorRole = new iam.Role(this, 'AgentProcessorRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        sharedInfra.stateTable.grantReadWriteData(agentProcessorRole);
        sharedInfra.claudeApiKeySecret.grantRead(agentProcessorRole);
        sharedInfra.jiraSecret.grantRead(agentProcessorRole);
        sharedInfra.codeContextBucket.grantReadWrite(agentProcessorRole);
        sharedInfra.agentQueue.grantConsumeMessages(agentProcessorRole);
        if (sharedInfra.bitbucketSecret) sharedInfra.bitbucketSecret.grantRead(agentProcessorRole);
        if (sharedInfra.githubSecret) sharedInfra.githubSecret.grantRead(agentProcessorRole);

        // ==================== LAMBDA FUNCTIONS ====================
        const agentWebhookHandler = new lambda.Function(this, 'AgentWebhookHandler', {
            functionName: `${prefix}-agent-webhook`,
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.AgentWebhookHandler::handleRequest',
            code: lambdaCode,
            memorySize: 512,
            timeout: cdk.Duration.minutes(1),
            environment: agentEnvironment,
            role: agentWebhookRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        const agentProcessorHandler = new lambda.Function(this, 'AgentProcessorHandler', {
            functionName: `${prefix}-agent-processor`,
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.AgentProcessorHandler::handleRequest',
            code: lambdaCode,
            memorySize: 2048,
            timeout: cdk.Duration.minutes(10),
            environment: agentEnvironment,
            role: agentProcessorRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        // SQS trigger
        agentProcessorHandler.addEventSource(
            new lambdaEventSources.SqsEventSource(sharedInfra.agentQueue, {
                batchSize: 1,
            })
        );

        // ==================== API GATEWAY ROUTES ====================
        const agentResource = api.root.addResource('agent');
        agentResource.addResource('webhook').addMethod(
            'POST',
            new apigateway.LambdaIntegration(agentWebhookHandler, { proxy: true })
        );
    }
}
