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
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as cwActions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as lambdaEventSources from 'aws-cdk-lib/aws-lambda-event-sources';
import { Construct } from 'constructs';
import * as path from 'path';

export class AiDrivenStack extends cdk.Stack {
    constructor(scope: Construct, id: string, props?: cdk.StackProps) {
        super(scope, id, props);

        // ==================== SECRETS ====================
        const claudeApiKeySecret = secretsmanager.Secret.fromSecretNameV2(this, 'ClaudeApiKey', 'ai-driven/claude-api-key');

        const bitbucketSecret = secretsmanager.Secret.fromSecretNameV2(this, 'BitbucketCredentials', 'ai-driven/bitbucket-credentials');

        const jiraSecret = secretsmanager.Secret.fromSecretNameV2(this, 'JiraCredentials', 'ai-driven/jira-credentials');

        const githubSecret = secretsmanager.Secret.fromSecretNameV2(this, 'GitHubCredentials', 'ai-driven/github-token');

        // Webhook verification secrets (operators set values post-deploy)
        const jiraWebhookTokenSecret = new secretsmanager.Secret(this, 'JiraWebhookTokenSecret', {
            secretName: 'ai-driven/jira-webhook-token',
            description: 'Pre-shared token for Jira → Lambda webhook verification (X-Jira-Webhook-Token header)',
            generateSecretString: {
                excludePunctuation: true,
                passwordLength: 32,
            },
        });

        const githubAgentWebhookSecret = new secretsmanager.Secret(this, 'GitHubAgentWebhookSecret', {
            secretName: 'ai-driven/github-agent-webhook-secret',
            description: 'HMAC secret for GitHub → Agent webhook signature verification',
            generateSecretString: {
                excludePunctuation: true,
                passwordLength: 40,
            },
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
            GITHUB_SECRET_ARN: githubSecret.secretArn,
            CODE_CONTEXT_BUCKET: codeContextBucket.bucketName,
            // Configurable limits (defaults match handler fallbacks)
            MAX_FILE_SIZE_CHARS: '100000',
            MAX_TOTAL_CONTEXT_CHARS: '3000000',
            MAX_FILE_SIZE_BYTES: '500000',
            MAX_CONTEXT_FOR_CLAUDE: '700000',
            CLAUDE_PROVIDER: 'BEDROCK',
            CLAUDE_MODEL: 'claude-sonnet-4-6',
            CLAUDE_MAX_TOKENS: '32768',
            CLAUDE_TEMPERATURE: '0.2',
            BEDROCK_REGION: 'ap-southeast-1',
            MERGE_WAIT_TIMEOUT_DAYS: '7',
            CONTEXT_MODE: 'FULL_REPO',
            PROMPT_VERSION: 'v1',
            BRANCH_PREFIX: 'ai/',
            DEFAULT_PLATFORM: 'GITHUB',
            DEFAULT_WORKSPACE: 'TeaSui',
            DEFAULT_REPO: 'claude-automation',
            JIRA_APP_USER_EMAIL: 'app-service-account@tyme.com',
            // Allow forcing a container recycle via --context deployId=...
            DEPLOY_ID: this.node.tryGetContext('deployId') || 'none',
            AUDIT_BUCKET_NAME: `ai-driven-audit-trail-${this.account}`,
        };

        // S3 bucket for audit trails - import existing bucket
        const auditBucket = s3.Bucket.fromBucketName(this, 'AuditTrailBucket', `ai-driven-audit-trail-${this.account}`);

        // ==================== SQS (Agent Task Queue) ====================
        const agentDlq = new sqs.Queue(this, 'AgentTasksDLQ', {
            queueName: 'ai-driven-agent-tasks-dlq.fifo',
            fifo: true,
            retentionPeriod: cdk.Duration.days(14),
        });

        const agentQueue = new sqs.Queue(this, 'AgentTasksQueue', {
            queueName: 'ai-driven-agent-tasks.fifo',
            fifo: true,
            contentBasedDeduplication: true,
            visibilityTimeout: cdk.Duration.seconds(600), // 10 min to match Lambda timeout
            deadLetterQueue: {
                queue: agentDlq,
                maxReceiveCount: 3,
            },
        });

        // ==================== SQS (Jira Workflow Queue) ====================
        // FIFO queue with deduplication for Jira webhook events
        // Deduplication window: 5 minutes (SQS default)
        // Prevents multiple Step Functions executions from duplicate webhook deliveries
        const jiraWorkflowDlq = new sqs.Queue(this, 'JiraWorkflowDLQ', {
            queueName: 'ai-driven-jira-workflow-dlq.fifo',
            fifo: true,
            retentionPeriod: cdk.Duration.days(14),
        });

        const jiraWorkflowQueue = new sqs.Queue(this, 'JiraWorkflowQueue', {
            queueName: 'ai-driven-jira-workflow.fifo',
            fifo: true,
            // NOT content-based - we use explicit MessageDeduplicationId = ticket+labels
            contentBasedDeduplication: false,
            visibilityTimeout: cdk.Duration.seconds(120), // 2 min for processing
            deadLetterQueue: {
                queue: jiraWorkflowDlq,
                maxReceiveCount: 3,
            },
        });

        // ==================== MCP GATEWAY LAMBDA (Node.js) ====================
        // Must be defined before agentEnvironment since it references mcpGatewayUrl
        const mcpGatewayCodePath = path.join(__dirname, '../../mcp-gateway/dist');
        const mcpGatewayCode = lambda.Code.fromAsset(mcpGatewayCodePath);

        const mcpGatewayRole = new iam.Role(this, 'McpGatewayRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        // Grant read access to secrets for MCP providers
        jiraSecret.grantRead(mcpGatewayRole);
        githubSecret.grantRead(mcpGatewayRole);

        const mcpGatewayHandler = new lambda.Function(this, 'McpGatewayHandler', {
            functionName: 'ai-driven-mcp-gateway',
            runtime: lambda.Runtime.NODEJS_20_X,
            handler: 'index.handler',
            code: mcpGatewayCode,
            memorySize: 512,
            timeout: cdk.Duration.seconds(30),
            environment: {
                CONTEXT7_ENABLED: 'true',
                // Credentials populated from Secrets Manager at runtime
                JIRA_SECRET_ARN: jiraSecret.secretArn,
                GITHUB_SECRET_ARN: githubSecret.secretArn,
            },
            role: mcpGatewayRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        // Add Function URL for MCP Gateway
        const mcpGatewayUrl = mcpGatewayHandler.addFunctionUrl({
            authType: lambda.FunctionUrlAuthType.AWS_IAM,
        });

        // Agent-specific environment variables
        const agentEnvironment = {
            ...lambdaEnvironment,
            AGENT_ENABLED: 'true',
            AGENT_TRIGGER_PREFIX: '@ai',
            AGENT_MAX_TOOL_TURNS: '10',
            AGENT_MAX_WALL_CLOCK_SECONDS: '720',
            AGENT_QUEUE_URL: agentQueue.queueUrl,
            // Phase 3: Guardrails + cost tracking
            AGENT_GUARDRAILS_ENABLED: 'true',
            AGENT_COST_BUDGET_PER_TICKET: '200000',
            AGENT_CLASSIFIER_USE_LLM: 'false',
            // Configurable @mention keyword (default: "ai" → @ai in comments)
            // Override with AGENT_MENTION_KEYWORD=claude to respond to @claude
            AGENT_MENTION_KEYWORD: 'ai',
            // Immutable Jira accountId of the bot service account (prevents self-loops)
            // Set to the Jira accountId of the Lambda execution identity (not display name)
            AGENT_BOT_ACCOUNT_ID: '',
            // GitHub agent webhook signature verification secret ARN
            // The Lambda fetches this secret on first call to verify X-Hub-Signature-256
            GITHUB_AGENT_WEBHOOK_SECRET_ARN: githubAgentWebhookSecret.secretArn,
            // Jira webhook pre-shared token — shared with jiraWebhookHandler; fetched lazily from SM
            JIRA_WEBHOOK_SECRET_ARN: jiraWebhookTokenSecret.secretArn,
            // Phase 4: MCP Gateway configuration
            // All MCP servers are now handled by the unified Node.js MCP Gateway Lambda
            MCP_GATEWAY_URL: mcpGatewayUrl.url,
            MCP_GATEWAY_ENABLED: 'true',
            // Legacy stdio config disabled - all MCP via gateway
            MCP_SERVERS_CONFIG: '[]',
            MCP_ALLOW_STDIO_IN_LAMBDA: 'false',
            // Audit Trail & Rate Limits
            AUDIT_RETENTION_YEARS: '3',
            MAX_REQUESTS_PER_USER_PER_HOUR: '10',
            MAX_REQUESTS_PER_TICKET_PER_HOUR: '20',
        };

        const javaRuntime = lambda.Runtime.JAVA_21;
        const lambdaCodePath = path.join(__dirname, '../../application/lambda-handlers/build/deployment');
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

        processingRole.addToPrincipalPolicy(new iam.PolicyStatement({
            actions: ["bedrock:InvokeModel"],
            resources: ["*"],
        }));

        claudeApiKeySecret.grantRead(processingRole);
        bitbucketSecret.grantRead(processingRole);
        jiraSecret.grantRead(processingRole);
        githubSecret.grantRead(processingRole);
        codeContextBucket.grantReadWrite(processingRole);
        auditBucket.grantReadWrite(processingRole);

        // Role for Jira Webhook Handler (DynamoDB, Secrets, StepFunctions)
        const jiraWebhookRole = new iam.Role(this, 'JiraWebhookRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        stateTable.grantReadWriteData(jiraWebhookRole);
        jiraSecret.grantRead(jiraWebhookRole);
        jiraWebhookTokenSecret.grantRead(jiraWebhookRole);

        // Role for MergeWait Handler (DynamoDB, StepFunctions SendTaskSuccess)
        const mergeWaitRole = new iam.Role(this, 'MergeWaitRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        stateTable.grantReadWriteData(mergeWaitRole);
        jiraSecret.grantRead(mergeWaitRole);

        // Role for Agent Webhook Handler (Jira, SQS send, DynamoDB)
        const agentWebhookRole = new iam.Role(this, 'AgentWebhookRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        stateTable.grantReadWriteData(agentWebhookRole);
        jiraSecret.grantRead(agentWebhookRole);
        githubSecret.grantRead(agentWebhookRole);
        githubAgentWebhookSecret.grantRead(agentWebhookRole);
        jiraWebhookTokenSecret.grantRead(agentWebhookRole);  // Jira pre-shared token (shared with jiraWebhookHandler)
        agentQueue.grantSendMessages(agentWebhookRole);

        // Role for Agent Processor Handler (SQS consume, DynamoDB, Secrets, S3, Claude)
        const agentProcessorRole = new iam.Role(this, 'AgentProcessorRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

        stateTable.grantReadWriteData(agentProcessorRole);
        claudeApiKeySecret.grantRead(agentProcessorRole);
        bitbucketSecret.grantRead(agentProcessorRole);
        jiraSecret.grantRead(agentProcessorRole);
        githubSecret.grantRead(agentProcessorRole);
        codeContextBucket.grantReadWrite(agentProcessorRole);
        agentQueue.grantConsumeMessages(agentProcessorRole);

        agentProcessorRole.addToPrincipalPolicy(new iam.PolicyStatement({
            actions: ["bedrock:InvokeModel"],
            resources: ["*"],
        }));
        // Grant agent processor permission to invoke MCP Gateway via Function URL
        mcpGatewayHandler.grantInvokeUrl(agentProcessorRole);

        // ==================== LAMBDA FUNCTIONS ====================

        // Jira webhook router (API Gateway → SQS FIFO)
        // Lightweight: validates, extracts ticket+labels, sends to SQS with dedup ID
        const jiraWebhookRouter = new lambda.Function(this, 'JiraWebhookRouter', {
            functionName: 'ai-driven-jira-webhook-router',
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.JiraWebhookRouter::handleRequest',
            code: lambdaCode,
            memorySize: 256, // Lightweight
            timeout: cdk.Duration.seconds(10), // Fast response to Jira
            environment: {
                ...lambdaEnvironment,
                JIRA_WORKFLOW_QUEUE_URL: jiraWorkflowQueue.queueUrl,
            },
            role: jiraWebhookRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        // Grant router permission to send messages to Jira workflow queue
        jiraWorkflowQueue.grantSendMessages(jiraWebhookRouter);

        // Jira webhook processor (SQS FIFO → Step Functions)
        // Triggered by SQS, deduplication already handled at queue level
        const jiraWebhookProcessor = new lambda.Function(this, 'JiraWebhookProcessor', {
            functionName: 'ai-driven-jira-webhook-processor',
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.JiraWebhookProcessor::handleRequest',
            code: lambdaCode,
            memorySize: 512,
            timeout: cdk.Duration.minutes(2),
            environment: lambdaEnvironment,
            role: jiraWebhookRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        // Processor consumes from Jira workflow queue
        jiraWebhookProcessor.addEventSource(
            new lambdaEventSources.SqsEventSource(jiraWorkflowQueue, {
                batchSize: 1, // Process one at a time for ordering
            })
        );

        // Keep old handler name for backwards compatibility with existing references
        const jiraWebhookHandler = jiraWebhookRouter;

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
            tracing: lambda.Tracing.ACTIVE,
        });

        // Download full repo archive from Source Control (Bitbucket/GitHub)
        const codeFetchHandler = new lambda.Function(this, 'CodeFetchHandler', {
            functionName: 'ai-driven-code-fetch',
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
            tracing: lambda.Tracing.ACTIVE,
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
            tracing: lambda.Tracing.ACTIVE,
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
            tracing: lambda.Tracing.ACTIVE,
        });

        // Agent webhook handler (thin: validate, ack, enqueue to SQS)
        const agentWebhookHandler = new lambda.Function(this, 'AgentWebhookHandler', {
            functionName: 'ai-driven-agent-webhook',
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.AgentWebhookHandler::handleRequest',
            code: lambdaCode,
            memorySize: 512,
            timeout: cdk.Duration.minutes(1),
            environment: agentEnvironment,
            role: agentWebhookRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        // Agent processor handler (heavy: runs orchestrator, triggered by SQS)
        const agentProcessorHandler = new lambda.Function(this, 'AgentProcessorHandler', {
            functionName: 'ai-driven-agent-processor',
            runtime: javaRuntime,
            handler: 'com.aidriven.lambda.AgentProcessorHandler::handleRequest',
            code: lambdaCode,
            memorySize: 2048,
            timeout: cdk.Duration.minutes(10),
            environment: agentEnvironment,
            role: agentProcessorRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        // SQS trigger for agent processor
        agentProcessorHandler.addEventSource(
            new lambdaEventSources.SqsEventSource(agentQueue, {
                batchSize: 1, // Process one task at a time
            })
        );

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

        // Step 2: Fetch code context from Source Control
        const fetchCodeTask = new tasks.LambdaInvoke(this, 'FetchCodeTask', {
            lambdaFunction: codeFetchHandler,
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
            taskTimeout: stepfunctions.Timeout.duration(
                cdk.Duration.days(parseInt(lambdaEnvironment.MERGE_WAIT_TIMEOUT_DAYS))
            ),
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
            stateMachineName: 'ai-driven-linear-workflow-v2',
            definitionBody: stepfunctions.DefinitionBody.fromChainable(workflowDefinition),
            timeout: cdk.Duration.days(parseInt(lambdaEnvironment.MERGE_WAIT_TIMEOUT_DAYS)),
            tracingEnabled: true,
            logs: {
                destination: new logs.LogGroup(this, 'WorkflowLogGroup', {
                    logGroupName: '/aws/vendedlogs/states/ai-driven-workflow',
                    retention: logs.RetentionDays.ONE_WEEK,
                    removalPolicy: cdk.RemovalPolicy.DESTROY,
                }),
                level: stepfunctions.LogLevel.ALL,
            },
        });

        // Grant permissions
        // Processor needs to start Step Functions executions (not the router)
        stateMachine.grantStartExecution(jiraWebhookProcessor);

        const callbackPolicy = new iam.Policy(this, 'CallbackPolicy', {
            statements: [
                new iam.PolicyStatement({
                    actions: ['states:SendTaskSuccess', 'states:SendTaskFailure'],
                    resources: [stateMachine.stateMachineArn],
                }),
            ],
        });
        callbackPolicy.attachToRole(mergeWaitRole);

        // Processor needs STATE_MACHINE_ARN to start executions
        jiraWebhookProcessor.addEnvironment('STATE_MACHINE_ARN', stateMachine.stateMachineArn);
        // Router needs JIRA_WEBHOOK_SECRET_ARN for token validation
        jiraWebhookRouter.addEnvironment('JIRA_WEBHOOK_SECRET_ARN', jiraWebhookTokenSecret.secretArn);

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

        // Agent webhook endpoint (agent comment processing)
        api.root.addResource('agent-webhook').addMethod(
            'POST',
            new apigateway.LambdaIntegration(agentWebhookHandler, { proxy: true })
        );

        // ==================== CLOUDWATCH DASHBOARD ====================
        const allHandlers = [
            { fn: jiraWebhookRouter, label: 'JiraRouter' },
            { fn: jiraWebhookProcessor, label: 'JiraProcessor' },
            { fn: fetchTicketHandler, label: 'FetchTicket' },
            { fn: codeFetchHandler, label: 'CodeFetch' },
            { fn: claudeInvokeHandler, label: 'ClaudeInvoke' },
            { fn: prCreatorHandler, label: 'PrCreator' },
            { fn: mergeWaitHandler, label: 'MergeWait' },
            { fn: agentWebhookHandler, label: 'AgentWebhook' },
            { fn: agentProcessorHandler, label: 'AgentProcessor' },
        ];

        const dashboard = new cloudwatch.Dashboard(this, 'OperationalDashboard', {
            dashboardName: 'ai-driven-operations',
        });

        // Row 1: Lambda invocations & errors
        dashboard.addWidgets(
            new cloudwatch.GraphWidget({
                title: 'Lambda Invocations',
                width: 12,
                left: allHandlers.map(h => h.fn.metricInvocations({ label: h.label })),
            }),
            new cloudwatch.GraphWidget({
                title: 'Lambda Errors',
                width: 12,
                left: allHandlers.map(h => h.fn.metricErrors({ label: h.label })),
            }),
        );

        // Row 2: Lambda duration & throttles
        dashboard.addWidgets(
            new cloudwatch.GraphWidget({
                title: 'Lambda Duration (P95)',
                width: 12,
                left: allHandlers.map(h => h.fn.metricDuration({
                    label: h.label,
                    statistic: 'p95',
                })),
            }),
            new cloudwatch.GraphWidget({
                title: 'Lambda Throttles',
                width: 12,
                left: allHandlers.map(h => h.fn.metricThrottles({ label: h.label })),
            }),
        );

        // Row 3: Step Functions workflow metrics
        dashboard.addWidgets(
            new cloudwatch.GraphWidget({
                title: 'Workflow Executions',
                width: 12,
                left: [
                    stateMachine.metricStarted({ label: 'Started' }),
                    stateMachine.metricSucceeded({ label: 'Succeeded' }),
                    stateMachine.metricFailed({ label: 'Failed' }),
                    stateMachine.metricTimedOut({ label: 'Timed Out' }),
                ],
            }),
            new cloudwatch.GraphWidget({
                title: 'Workflow Duration',
                width: 12,
                left: [
                    stateMachine.metricTime({ label: 'Avg Duration', statistic: 'avg' }),
                    stateMachine.metricTime({ label: 'P95 Duration', statistic: 'p95' }),
                ],
            }),
        );

        // Row 4: DynamoDB & Alarm summary
        dashboard.addWidgets(
            new cloudwatch.GraphWidget({
                title: 'DynamoDB Read/Write Capacity',
                width: 12,
                left: [
                    stateTable.metricConsumedReadCapacityUnits({ label: 'Read CU' }),
                    stateTable.metricConsumedWriteCapacityUnits({ label: 'Write CU' }),
                ],
            }),
            new cloudwatch.SingleValueWidget({
                title: 'Total Workflow Failures (24h)',
                width: 12,
                metrics: [
                    stateMachine.metricFailed({ period: cdk.Duration.days(1), label: 'Failures' }),
                ],
            }),
        );

        // ==================== SECURITY ALARMS ====================

        // SNS topic for operator security and operational alerts.
        // Subscribe your email or PagerDuty endpoint after deployment:
        //   aws sns subscribe --topic-arn <AlertsTopicArn> --protocol email --notification-endpoint ops@example.com
        const alertsTopic = new sns.Topic(this, 'OperationalAlerts', {
            topicName: 'ai-driven-operational-alerts',
            displayName: 'AI Driven Operational Alerts',
        });

        // Metric filter: Jira pipeline webhook token verification skipped
        // Triggers when JIRA_WEBHOOK_SECRET_ARN is set but the secret value is null/blank in SM
        const jiraTokenSkippedFilter = new logs.MetricFilter(this, 'JiraWebhookTokenSkippedFilter', {
            logGroup: jiraWebhookHandler.logGroup,
            metricNamespace: 'AiDriven/WebhookSecurity',
            metricName: 'JiraWebhookTokenVerificationSkipped',
            filterPattern: logs.FilterPattern.literal('"webhook token verification skipped"'),
            metricValue: '1',
            defaultValue: 0,
        });

        // Metric filter: Agent webhook Jira token verification skipped
        const agentJiraTokenSkippedFilter = new logs.MetricFilter(this, 'AgentJiraTokenSkippedFilter', {
            logGroup: agentWebhookHandler.logGroup,
            metricNamespace: 'AiDriven/WebhookSecurity',
            metricName: 'AgentJiraTokenVerificationSkipped',
            filterPattern: logs.FilterPattern.literal('"webhook token verification skipped"'),
            metricValue: '1',
            defaultValue: 0,
        });

        // Metric filter: GitHub HMAC signature verification skipped
        const githubHmacSkippedFilter = new logs.MetricFilter(this, 'GitHubHmacSkippedFilter', {
            logGroup: agentWebhookHandler.logGroup,
            metricNamespace: 'AiDriven/WebhookSecurity',
            metricName: 'GitHubSignatureVerificationSkipped',
            filterPattern: logs.FilterPattern.literal('"GitHub signature verification skipped"'),
            metricValue: '1',
            defaultValue: 0,
        });

        // Alarm: Jira pipeline webhook token skipped — endpoint may be unprotected
        const jiraTokenSkippedAlarm = new cloudwatch.Alarm(this, 'JiraWebhookTokenSkippedAlarm', {
            alarmName: 'ai-driven-jira-webhook-token-skipped',
            alarmDescription:
                'Jira pipeline webhook (/jira-webhook) token verification is being skipped. ' +
                'JIRA_WEBHOOK_SECRET_ARN is set but the secret value may be blank in Secrets Manager. ' +
                'Run: aws secretsmanager put-secret-value --secret-id <JiraWebhookTokenSecretArn> --secret-string "<token>"',
            metric: jiraTokenSkippedFilter.metric({ period: cdk.Duration.minutes(5) }),
            threshold: 1,
            evaluationPeriods: 1,
            comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        });
        jiraTokenSkippedAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Alarm: Agent webhook Jira token skipped
        const agentJiraTokenSkippedAlarm = new cloudwatch.Alarm(this, 'AgentJiraTokenSkippedAlarm', {
            alarmName: 'ai-driven-agent-jira-token-skipped',
            alarmDescription:
                'Agent webhook (/agent-webhook) Jira token verification is being skipped for Jira-sourced events. ' +
                'Jira agent comments are arriving without signature verification.',
            metric: agentJiraTokenSkippedFilter.metric({ period: cdk.Duration.minutes(5) }),
            threshold: 1,
            evaluationPeriods: 1,
            comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        });
        agentJiraTokenSkippedAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Alarm: GitHub HMAC verification skipped
        const githubHmacSkippedAlarm = new cloudwatch.Alarm(this, 'GitHubHmacSkippedAlarm', {
            alarmName: 'ai-driven-github-hmac-skipped',
            alarmDescription:
                'GitHub agent webhook HMAC verification is being skipped. ' +
                'GITHUB_AGENT_WEBHOOK_SECRET_ARN is set but the secret value may be blank in Secrets Manager. ' +
                'GitHub-sourced @ai comments are arriving without HMAC verification.',
            metric: githubHmacSkippedFilter.metric({ period: cdk.Duration.minutes(5) }),
            threshold: 1,
            evaluationPeriods: 1,
            comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        });
        githubHmacSkippedAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Alarm: JiraWebhookRouter Lambda errors
        const jiraRouterErrorsAlarm = new cloudwatch.Alarm(this, 'JiraRouterErrorsAlarm', {
            alarmName: 'ai-driven-jira-router-errors',
            alarmDescription: 'JiraWebhookRouter Lambda errors exceed threshold. Check CloudWatch logs.',
            metric: jiraWebhookRouter.metricErrors({ period: cdk.Duration.minutes(5) }),
            threshold: 3,
            evaluationPeriods: 2,
            comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        });
        jiraRouterErrorsAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Alarm: JiraWebhookProcessor Lambda errors
        const jiraProcessorErrorsAlarm = new cloudwatch.Alarm(this, 'JiraProcessorErrorsAlarm', {
            alarmName: 'ai-driven-jira-processor-errors',
            alarmDescription: 'JiraWebhookProcessor Lambda errors exceed threshold. Check CloudWatch logs.',
            metric: jiraWebhookProcessor.metricErrors({ period: cdk.Duration.minutes(5) }),
            threshold: 3,
            evaluationPeriods: 2,
            comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        });
        jiraProcessorErrorsAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Alarm: AgentWebhookHandler Lambda errors
        const agentWebhookErrorsAlarm = new cloudwatch.Alarm(this, 'AgentWebhookErrorsAlarm', {
            alarmName: 'ai-driven-agent-webhook-errors',
            alarmDescription: 'AgentWebhookHandler Lambda errors exceed threshold. Check CloudWatch logs.',
            metric: agentWebhookHandler.metricErrors({ period: cdk.Duration.minutes(5) }),
            threshold: 3,
            evaluationPeriods: 2,
            comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        });
        agentWebhookErrorsAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Row 5 of dashboard: Security alarm status panel
        dashboard.addWidgets(
            new cloudwatch.AlarmStatusWidget({
                title: 'Webhook Security & Error Alarms',
                width: 24,
                alarms: [
                    jiraTokenSkippedAlarm,
                    agentJiraTokenSkippedAlarm,
                    githubHmacSkippedAlarm,
                    jiraRouterErrorsAlarm,
                    jiraProcessorErrorsAlarm,
                    agentWebhookErrorsAlarm,
                ],
            }),
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

        new cdk.CfnOutput(this, 'AgentWebhookUrl', {
            value: `${api.url}agent-webhook`,
            description: 'Agent Webhook URL',
        });

        new cdk.CfnOutput(this, 'AgentQueueUrl', {
            value: agentQueue.queueUrl,
            description: 'Agent Tasks SQS FIFO Queue URL',
        });

        new cdk.CfnOutput(this, 'DynamoDBTableName', {
            value: stateTable.tableName,
            description: 'DynamoDB State Table Name',
        });

        new cdk.CfnOutput(this, 'StateMachineArn', {
            value: stateMachine.stateMachineArn,
            description: 'Step Functions State Machine ARN',
        });

        // ── Operator setup: after deploy, populate these secrets then configure webhooks ──
        new cdk.CfnOutput(this, 'JiraWebhookTokenSecretArn', {
            value: jiraWebhookTokenSecret.secretArn,
            description: 'Jira webhook token Secret ARN — run: aws secretsmanager put-secret-value --secret-id <arn> --secret-string "<token>", then set the same token in Jira webhook config (Authorization: Bearer <token>)',
        });

        new cdk.CfnOutput(this, 'GitHubAgentWebhookSecretArn', {
            value: githubAgentWebhookSecret.secretArn,
            description: 'GitHub agent webhook HMAC secret ARN — run: aws secretsmanager put-secret-value --secret-id <arn> --secret-string "<secret>", then set the same secret in the GitHub repo webhook settings',
        });

        new cdk.CfnOutput(this, 'AlertsTopicArn', {
            value: alertsTopic.topicArn,
            description:
                'SNS topic ARN for security and operational alerts. ' +
                'Subscribe your ops email: aws sns subscribe --topic-arn <AlertsTopicArn> --protocol email --notification-endpoint ops@example.com',
        });

        new cdk.CfnOutput(this, 'McpGatewayUrl', {
            value: mcpGatewayUrl.url,
            description: 'MCP Gateway Lambda Function URL - provides unified access to Context7, GitHub, and Jira MCP tools',
        });
    }
}
