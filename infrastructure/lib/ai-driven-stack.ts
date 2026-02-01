import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as stepfunctions from 'aws-cdk-lib/aws-stepfunctions';
import * as tasks from 'aws-cdk-lib/aws-stepfunctions-tasks';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';
import * as path from 'path';

export class AiDrivenStack extends cdk.Stack {
    constructor(scope: Construct, id: string, props?: cdk.StackProps) {
        super(scope, id, props);

        // ==================== SECRETS ====================
        const claudeApiKeySecret = new secretsmanager.Secret(this, 'ClaudeApiKey', {
            secretName: 'ai-driven/claude-api-key',
            description: 'Claude API key for direct API calls',
        });

        const bitbucketSecret = new secretsmanager.Secret(this, 'BitbucketCredentials', {
            secretName: 'ai-driven/bitbucket-credentials',
            description: 'Bitbucket Cloud app password',
        });

        const jiraSecret = new secretsmanager.Secret(this, 'JiraCredentials', {
            secretName: 'ai-driven/jira-credentials',
            description: 'Jira Cloud API token',
        });

        // ==================== DYNAMODB ====================
        const stateTable = new dynamodb.Table(this, 'StateTable', {
            tableName: 'ai-driven-state',
            partitionKey: { name: 'PK', type: dynamodb.AttributeType.STRING },
            sortKey: { name: 'SK', type: dynamodb.AttributeType.STRING },
            billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
            timeToLiveAttribute: 'ttl',
            removalPolicy: cdk.RemovalPolicy.DESTROY,
            pointInTimeRecoverySpecification: { pointInTimeRecoveryEnabled: true },
        });

        stateTable.addGlobalSecondaryIndex({
            indexName: 'GSI1',
            partitionKey: { name: 'GSI1PK', type: dynamodb.AttributeType.STRING },
            sortKey: { name: 'GSI1SK', type: dynamodb.AttributeType.STRING },
            projectionType: dynamodb.ProjectionType.ALL,
        });

        // ==================== S3 (Code Context Storage) ====================
        const codeContextBucket = new s3.Bucket(this, 'CodeContextBucket', {
            bucketName: `ai-driven-code-context-${cdk.Aws.ACCOUNT_ID}`,
            removalPolicy: cdk.RemovalPolicy.DESTROY,
            autoDeleteObjects: true,
            lifecycleRules: [
                {
                    expiration: cdk.Duration.days(14),
                    prefix: 'context/',
                },
            ],
            encryption: s3.BucketEncryption.S3_MANAGED,
            blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
        });

        // ==================== LAMBDA COMMON CONFIG ====================
        const lambdaEnvironment = {
            DYNAMODB_TABLE_NAME: stateTable.tableName,
            CLAUDE_SECRET_ARN: claudeApiKeySecret.secretArn,
            BITBUCKET_SECRET_ARN: bitbucketSecret.secretArn,
            JIRA_SECRET_ARN: jiraSecret.secretArn,
            CODE_CONTEXT_BUCKET: codeContextBucket.bucketName,
        };

        const javaRuntime = lambda.Runtime.JAVA_21;
        const lambdaCodePath = path.join(__dirname, '../../application/lambda-handlers/build/libs/lambda-handlers-all.jar');
        const lambdaCode = lambda.Code.fromAsset(lambdaCodePath);

        // ==================== IAM ROLES ====================

        // Role for Processing Handlers (DynamoDB, Secrets, S3)
        const processingRole = new iam.Role(this, 'ProcessingRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        stateTable.grantReadWriteData(processingRole);
        claudeApiKeySecret.grantRead(processingRole);
        bitbucketSecret.grantRead(processingRole);
        jiraSecret.grantRead(processingRole);
        codeContextBucket.grantReadWrite(processingRole);

        // Role for Jira Webhook Handler (DynamoDB, Secrets, StepFunctions)
        const jiraWebhookRole = new iam.Role(this, 'JiraWebhookRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        stateTable.grantReadWriteData(jiraWebhookRole);
        jiraSecret.grantRead(jiraWebhookRole);

        // Role for MergeWait Handler (DynamoDB, StepFunctions SendTaskSuccess)
        const mergeWaitRole = new iam.Role(this, 'MergeWaitRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        stateTable.grantReadWriteData(mergeWaitRole);
        jiraSecret.grantRead(mergeWaitRole);

        // ==================== LAMBDA FUNCTIONS ====================

        // Jira webhook handler (entry point)
        const jiraWebhookHandler = new lambda.Function(this, 'JiraWebhookHandler', {
            functionName: 'ai-driven-jira-webhook',
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.JiraWebhookHandler::handleRequest',
            code: lambdaCode,
            memorySize: 512,
            timeout: cdk.Duration.minutes(1),
            environment: lambdaEnvironment,
            role: jiraWebhookRole,
        });

        // Fetch ticket details from Jira
        const fetchTicketHandler = new lambda.Function(this, 'FetchTicketHandler', {
            functionName: 'ai-driven-fetch-ticket',
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.FetchTicketHandler::handleRequest',
            code: lambdaCode,
            memorySize: 512,
            timeout: cdk.Duration.minutes(5),
            environment: lambdaEnvironment,
            role: processingRole,
        });

        // Download full repo archive from Bitbucket
        const bitbucketFetchHandler = new lambda.Function(this, 'BitbucketFetchHandler', {
            functionName: 'ai-driven-bitbucket-fetch',
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.BitbucketFetchHandler::handleRequest',
            code: lambdaCode,
            memorySize: 2048,
            timeout: cdk.Duration.minutes(10),
            ephemeralStorageSize: cdk.Size.gibibytes(2),
            environment: lambdaEnvironment,
            role: processingRole,
        });

        // Generate code using Claude AI
        const claudeInvokeHandler = new lambda.Function(this, 'ClaudeInvokeHandler', {
            functionName: 'ai-driven-claude-invoke',
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.ClaudeInvokeHandler::handleRequest',
            code: lambdaCode,
            memorySize: 2048,
            timeout: cdk.Duration.minutes(15),
            environment: lambdaEnvironment,
            role: processingRole,
        });

        // Create PR in Bitbucket
        const prCreatorHandler = new lambda.Function(this, 'PrCreatorHandler', {
            functionName: 'ai-driven-pr-creator',
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.PrCreatorHandler::handleRequest',
            code: lambdaCode,
            memorySize: 512,
            timeout: cdk.Duration.minutes(5),
            environment: lambdaEnvironment,
            role: processingRole,
        });

        // Merge wait handler (task token callback)
        const mergeWaitHandler = new lambda.Function(this, 'MergeWaitHandler', {
            functionName: 'ai-driven-merge-wait',
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.MergeWaitHandler::handleRequest',
            code: lambdaCode,
            memorySize: 512,
            timeout: cdk.Duration.minutes(1),
            environment: lambdaEnvironment,
            role: mergeWaitRole,
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
            cause: 'One or more steps failed after retries. Check CloudWatch logs for details.',
        });

        // Step 1: Fetch ticket details from Jira
        const fetchTicketTask = new tasks.LambdaInvoke(this, 'FetchTicketTask', {
            lambdaFunction: fetchTicketHandler,
            outputPath: '$.Payload',
            comment: 'Fetch full ticket details from Jira',
        });
        fetchTicketTask.addRetry(lambdaRetryProps);
        fetchTicketTask.addCatch(workflowFailed, { resultPath: '$.error' });

        // Step 2: Fetch code context from Bitbucket
        const fetchCodeTask = new tasks.LambdaInvoke(this, 'FetchCodeTask', {
            lambdaFunction: bitbucketFetchHandler,
            outputPath: '$.Payload',
            comment: 'Download full repo and store code context in S3',
        });
        fetchCodeTask.addRetry(lambdaRetryProps);
        fetchCodeTask.addCatch(workflowFailed, { resultPath: '$.error' });

        // Step 3: Generate code using Claude AI
        const claudeInvokeTask = new tasks.LambdaInvoke(this, 'ClaudeInvokeTask', {
            lambdaFunction: claudeInvokeHandler,
            outputPath: '$.Payload',
            comment: 'Generate code using Claude AI (Direct API)',
        });
        claudeInvokeTask.addRetry({
            ...lambdaRetryProps,
            maxAttempts: 2, // Fewer retries for AI calls (expensive)
        });
        claudeInvokeTask.addCatch(workflowFailed, { resultPath: '$.error' });

        // Step 4: Create PR in Bitbucket
        const createPrTask = new tasks.LambdaInvoke(this, 'CreatePrTask', {
            lambdaFunction: prCreatorHandler,
            outputPath: '$.Payload',
            comment: 'Create pull request in Bitbucket',
        });
        createPrTask.addRetry(lambdaRetryProps);
        createPrTask.addCatch(workflowFailed, { resultPath: '$.error' });

        // Step 5: Wait for PR merge (task token callback)
        const waitForMergeTask = new tasks.LambdaInvoke(this, 'WaitForMergeTask', {
            lambdaFunction: mergeWaitHandler,
            integrationPattern: stepfunctions.IntegrationPattern.WAIT_FOR_TASK_TOKEN,
            payload: stepfunctions.TaskInput.fromObject({
                token: stepfunctions.JsonPath.taskToken,
                ticketId: stepfunctions.JsonPath.stringAt('$.ticketId'),
                ticketKey: stepfunctions.JsonPath.stringAt('$.ticketKey'),
                prUrl: stepfunctions.JsonPath.stringAt('$.prUrl'),
            }),
            taskTimeout: stepfunctions.Timeout.duration(cdk.Duration.days(7)),
            comment: 'Wait for PR to be merged (callback from webhook)',
        });

        const workflowComplete = new stepfunctions.Succeed(this, 'WorkflowComplete', {
            comment: 'PR merged, workflow completed successfully',
        });

        // Dry-run check: skip wait-for-merge if dry-run or PR not created
        const dryRunChoice = new stepfunctions.Choice(this, 'DryRunCheck')
            .when(
                stepfunctions.Condition.booleanEquals('$.dryRun', true),
                workflowComplete
            )
            .when(
                stepfunctions.Condition.booleanEquals('$.prCreated', false),
                workflowComplete
            )
            .otherwise(waitForMergeTask.next(workflowComplete));

        // Linear workflow: FetchTicket → FetchCode → ClaudeInvoke → CreatePR → [DryRunCheck]
        const workflowDefinition = fetchTicketTask
            .next(fetchCodeTask)
            .next(claudeInvokeTask)
            .next(createPrTask)
            .next(dryRunChoice);

        const stateMachine = new stepfunctions.StateMachine(this, 'LinearStateMachine', {
            stateMachineName: 'ai-driven-linear-workflow',
            definitionBody: stepfunctions.DefinitionBody.fromChainable(workflowDefinition),
            timeout: cdk.Duration.days(7),
            tracingEnabled: true,
            logs: {
                destination: new logs.LogGroup(this, 'LinearStateMachineLogGroup', {
                    retention: logs.RetentionDays.ONE_WEEK,
                }),
                level: stepfunctions.LogLevel.ALL,
            },
        });

        // Grant permissions
        stateMachine.grantStartExecution(jiraWebhookHandler);

        const callbackPolicy = new iam.Policy(this, 'CallbackPolicy', {
            statements: [
                new iam.PolicyStatement({
                    actions: ['states:SendTaskSuccess', 'states:SendTaskFailure'],
                    resources: [stateMachine.stateMachineArn],
                }),
            ],
        });
        callbackPolicy.attachToRole(mergeWaitRole);

        jiraWebhookHandler.addEnvironment('STATE_MACHINE_ARN', stateMachine.stateMachineArn);

        // ==================== API GATEWAY ====================
        const api = new apigateway.RestApi(this, 'WebhookApi', {
            restApiName: 'ai-driven-webhook',
            description: 'Webhook endpoints for Jira and Bitbucket events',
            deployOptions: {
                stageName: 'prod',
                throttlingRateLimit: 100,
                throttlingBurstLimit: 200,
            },
        });

        // Jira webhook endpoint (triggers linear workflow)
        api.root.addResource('jira-webhook').addMethod(
            'POST',
            new apigateway.LambdaIntegration(jiraWebhookHandler, { proxy: true })
        );

        // Bitbucket merge webhook endpoint (task token callback)
        api.root.addResource('merge-webhook').addMethod(
            'POST',
            new apigateway.LambdaIntegration(mergeWaitHandler, { proxy: true })
        );

        // ==================== OUTPUTS ====================
        new cdk.CfnOutput(this, 'JiraWebhookUrl', {
            value: `${api.url}jira-webhook`,
            description: 'Jira Webhook URL',
        });

        new cdk.CfnOutput(this, 'MergeWebhookUrl', {
            value: `${api.url}merge-webhook`,
            description: 'Bitbucket Merge Webhook URL',
        });

        new cdk.CfnOutput(this, 'DynamoDBTableName', {
            value: stateTable.tableName,
            description: 'DynamoDB State Table Name',
        });

        new cdk.CfnOutput(this, 'StateMachineArn', {
            value: stateMachine.stateMachineArn,
            description: 'Step Functions State Machine ARN',
        });
    }
}
