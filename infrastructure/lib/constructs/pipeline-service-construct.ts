import * as cdk from 'aws-cdk-lib';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as stepfunctions from 'aws-cdk-lib/aws-stepfunctions';
import * as tasks from 'aws-cdk-lib/aws-stepfunctions-tasks';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';
import { SharedInfraConstruct } from './shared-infra-construct';
import { TenantConfig } from '../config/tenant-config';
import * as path from 'path';

/**
 * Pipeline Service: deterministic, single-shot workflow.
 * Trigger: Jira label "ai-generate" -> webhook -> Step Functions.
 * Flow: FetchTicket -> CodeFetch -> ClaudeInvoke -> PrCreator -> MergeWait.
 *
 * This construct is independently deployable. A tenant that only needs
 * the pipeline workflow can deploy SharedInfra + PipelineService.
 */
export interface PipelineServiceProps {
    readonly tenantConfig: TenantConfig;
    readonly sharedInfra: SharedInfraConstruct;
    readonly api: apigateway.RestApi;
}

export class PipelineServiceConstruct extends Construct {
    public readonly stateMachine: stepfunctions.StateMachine;

    constructor(scope: Construct, id: string, props: PipelineServiceProps) {
        super(scope, id);

        const { tenantConfig, sharedInfra, api } = props;
        const prefix = tenantConfig.resourcePrefix;

        const javaRuntime = lambda.Runtime.JAVA_21;
        const lambdaCodePath = path.join(__dirname, '../../../application/pipeline-handlers/build/libs/pipeline-handlers-all.jar');
        const lambdaCode = lambda.Code.fromAsset(lambdaCodePath);

        // ==================== ENVIRONMENT ====================
        const lambdaEnvironment: Record<string, string> = {
            TENANT_ID: tenantConfig.tenantId,
            DYNAMODB_TABLE_NAME: sharedInfra.stateTable.tableName,
            CLAUDE_SECRET_ARN: sharedInfra.claudeApiKeySecret.secretArn,
            JIRA_SECRET_ARN: sharedInfra.jiraSecret.secretArn,
            BITBUCKET_SECRET_ARN: sharedInfra.bitbucketSecret?.secretArn ?? '',
            GITHUB_SECRET_ARN: sharedInfra.githubSecret?.secretArn ?? '',
            CODE_CONTEXT_BUCKET: sharedInfra.codeContextBucket.bucketName,
            MAX_FILE_SIZE_CHARS: String(tenantConfig.limits.maxFileSizeChars),
            MAX_TOTAL_CONTEXT_CHARS: String(tenantConfig.limits.maxTotalContextChars),
            MAX_FILE_SIZE_BYTES: String(tenantConfig.limits.maxFileSizeBytes),
            MAX_CONTEXT_FOR_CLAUDE: String(tenantConfig.limits.maxContextForClaude),
            CLAUDE_MODEL: tenantConfig.claudeModel,
            CLAUDE_MAX_TOKENS: String(tenantConfig.claudeMaxTokens),
            CLAUDE_TEMPERATURE: String(tenantConfig.claudeTemperature),
            MERGE_WAIT_TIMEOUT_DAYS: String(tenantConfig.mergeWaitTimeoutDays),
            CONTEXT_MODE: tenantConfig.contextMode,
            PROMPT_VERSION: tenantConfig.promptVersion,
            BRANCH_PREFIX: tenantConfig.branchPrefix,
            DEFAULT_PLATFORM: tenantConfig.defaultPlatform,
            DEFAULT_WORKSPACE: tenantConfig.defaultWorkspace,
            DEFAULT_REPO: tenantConfig.defaultRepo,
        };

        // ==================== IAM ROLES ====================
        const processingRole = new iam.Role(this, 'ProcessingRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        sharedInfra.stateTable.grantReadWriteData(processingRole);
        sharedInfra.claudeApiKeySecret.grantRead(processingRole);
        sharedInfra.jiraSecret.grantRead(processingRole);
        sharedInfra.codeContextBucket.grantReadWrite(processingRole);
        if (sharedInfra.bitbucketSecret) sharedInfra.bitbucketSecret.grantRead(processingRole);
        if (sharedInfra.githubSecret) sharedInfra.githubSecret.grantRead(processingRole);

        const jiraWebhookRole = new iam.Role(this, 'JiraWebhookRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        sharedInfra.stateTable.grantReadWriteData(jiraWebhookRole);
        sharedInfra.jiraSecret.grantRead(jiraWebhookRole);

        const mergeWaitRole = new iam.Role(this, 'MergeWaitRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        sharedInfra.stateTable.grantReadWriteData(mergeWaitRole);
        sharedInfra.jiraSecret.grantRead(mergeWaitRole);

        // ==================== LAMBDA FUNCTIONS ====================
        const jiraWebhookHandler = new lambda.Function(this, 'JiraWebhookHandler', {
            functionName: `${prefix}-jira-webhook`,
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.JiraWebhookHandler::handleRequest',
            code: lambdaCode,
            memorySize: 512,
            timeout: cdk.Duration.minutes(1),
            environment: lambdaEnvironment,
            role: jiraWebhookRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        const fetchTicketHandler = new lambda.Function(this, 'FetchTicketHandler', {
            functionName: `${prefix}-fetch-ticket`,
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.FetchTicketHandler::handleRequest',
            code: lambdaCode,
            memorySize: 512,
            timeout: cdk.Duration.minutes(5),
            environment: lambdaEnvironment,
            role: processingRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        const codeFetchHandler = new lambda.Function(this, 'CodeFetchHandler', {
            functionName: `${prefix}-code-fetch`,
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.CodeFetchHandler::handleRequest',
            code: lambdaCode,
            memorySize: 2048,
            timeout: cdk.Duration.minutes(10),
            ephemeralStorageSize: cdk.Size.gibibytes(2),
            environment: lambdaEnvironment,
            role: processingRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        const claudeInvokeHandler = new lambda.Function(this, 'ClaudeInvokeHandler', {
            functionName: `${prefix}-claude-invoke`,
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.ClaudeInvokeHandler::handleRequest',
            code: lambdaCode,
            memorySize: 2048,
            timeout: cdk.Duration.minutes(15),
            environment: lambdaEnvironment,
            role: processingRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        const prCreatorHandler = new lambda.Function(this, 'PrCreatorHandler', {
            functionName: `${prefix}-pr-creator`,
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.PrCreatorHandler::handleRequest',
            code: lambdaCode,
            memorySize: 512,
            timeout: cdk.Duration.minutes(5),
            environment: lambdaEnvironment,
            role: processingRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        const mergeWaitHandler = new lambda.Function(this, 'MergeWaitHandler', {
            functionName: `${prefix}-merge-wait`,
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.MergeWaitHandler::handleRequest',
            code: lambdaCode,
            memorySize: 512,
            timeout: cdk.Duration.minutes(1),
            environment: lambdaEnvironment,
            role: mergeWaitRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        // ==================== STEP FUNCTIONS ====================
        const lambdaRetryProps = {
            errors: ['Lambda.ServiceException', 'Lambda.AWSLambdaException', 'Lambda.SdkClientException', 'States.TaskFailed'],
            interval: cdk.Duration.seconds(5),
            maxAttempts: 3,
            backoffRate: 2,
        };

        const workflowFailed = new stepfunctions.Fail(this, 'WorkflowFailed', {
            error: 'WorkflowExecutionFailed',
            cause: 'One or more steps failed after retries.',
        });

        const fetchTicketTask = new tasks.LambdaInvoke(this, 'FetchTicketTask', {
            lambdaFunction: fetchTicketHandler,
            outputPath: '$.Payload',
            comment: 'Fetch full ticket details from Jira',
        });
        fetchTicketTask.addRetry(lambdaRetryProps);
        fetchTicketTask.addCatch(workflowFailed, { resultPath: '$.error' });

        const fetchCodeTask = new tasks.LambdaInvoke(this, 'FetchCodeTask', {
            lambdaFunction: codeFetchHandler,
            outputPath: '$.Payload',
            comment: 'Download repo and store code context in S3',
        });
        fetchCodeTask.addRetry(lambdaRetryProps);
        fetchCodeTask.addCatch(workflowFailed, { resultPath: '$.error' });

        const claudeInvokeTask = new tasks.LambdaInvoke(this, 'ClaudeInvokeTask', {
            lambdaFunction: claudeInvokeHandler,
            outputPath: '$.Payload',
            comment: 'Generate code using Claude AI',
        });
        claudeInvokeTask.addRetry({ ...lambdaRetryProps, maxAttempts: 2 });
        claudeInvokeTask.addCatch(workflowFailed, { resultPath: '$.error' });

        const createPrTask = new tasks.LambdaInvoke(this, 'CreatePrTask', {
            lambdaFunction: prCreatorHandler,
            outputPath: '$.Payload',
            comment: 'Create pull request',
        });
        createPrTask.addRetry(lambdaRetryProps);
        createPrTask.addCatch(workflowFailed, { resultPath: '$.error' });

        const waitForMergeTask = new tasks.LambdaInvoke(this, 'WaitForMergeTask', {
            lambdaFunction: mergeWaitHandler,
            integrationPattern: stepfunctions.IntegrationPattern.WAIT_FOR_TASK_TOKEN,
            payload: stepfunctions.TaskInput.fromObject({
                token: stepfunctions.JsonPath.taskToken,
                ticketId: stepfunctions.JsonPath.stringAt('$.ticketId'),
                ticketKey: stepfunctions.JsonPath.stringAt('$.ticketKey'),
                prUrl: stepfunctions.JsonPath.stringAt('$.prUrl'),
            }),
            taskTimeout: stepfunctions.Timeout.duration(
                cdk.Duration.days(tenantConfig.mergeWaitTimeoutDays)
            ),
            comment: 'Wait for PR to be merged (callback)',
        });

        const workflowComplete = new stepfunctions.Succeed(this, 'WorkflowComplete', {
            comment: 'PR merged, workflow completed successfully',
        });

        const dryRunChoice = new stepfunctions.Choice(this, 'DryRunCheck')
            .when(stepfunctions.Condition.booleanEquals('$.dryRun', true), workflowComplete)
            .when(stepfunctions.Condition.booleanEquals('$.prCreated', false), workflowComplete)
            .otherwise(waitForMergeTask.next(workflowComplete));

        const workflowDefinition = fetchTicketTask
            .next(fetchCodeTask)
            .next(claudeInvokeTask)
            .next(createPrTask)
            .next(dryRunChoice);

        this.stateMachine = new stepfunctions.StateMachine(this, 'LinearStateMachine', {
            stateMachineName: `${prefix}-linear-workflow`,
            definitionBody: stepfunctions.DefinitionBody.fromChainable(workflowDefinition),
            timeout: cdk.Duration.days(tenantConfig.mergeWaitTimeoutDays),
            tracingEnabled: true,
            logs: {
                destination: new logs.LogGroup(this, 'WorkflowLogGroup', {
                    retention: logs.RetentionDays.ONE_WEEK,
                }),
                level: stepfunctions.LogLevel.ALL,
            },
        });

        this.stateMachine.grantStartExecution(jiraWebhookHandler);

        const callbackPolicy = new iam.Policy(this, 'CallbackPolicy', {
            statements: [
                new iam.PolicyStatement({
                    actions: ['states:SendTaskSuccess', 'states:SendTaskFailure'],
                    resources: [this.stateMachine.stateMachineArn],
                }),
            ],
        });
        callbackPolicy.attachToRole(mergeWaitRole);

        jiraWebhookHandler.addEnvironment('STATE_MACHINE_ARN', this.stateMachine.stateMachineArn);

        // ==================== API GATEWAY ROUTES ====================
        api.root.addResource('jira-webhook').addMethod(
            'POST',
            new apigateway.LambdaIntegration(jiraWebhookHandler, { proxy: true })
        );

        api.root.addResource('merge-webhook').addMethod(
            'POST',
            new apigateway.LambdaIntegration(mergeWaitHandler, { proxy: true })
        );
    }
}
