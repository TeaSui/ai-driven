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
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
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
            description: 'Pre-shared token for Jira webhook verification (X-Jira-Webhook-Token header)',
            generateSecretString: {
                excludePunctuation: true,
                passwordLength: 32,
            },
        });

        const githubAgentWebhookSecret = new secretsmanager.Secret(this, 'GitHubAgentWebhookSecret', {
            secretName: 'ai-driven/github-agent-webhook-secret',
            description: 'HMAC secret for GitHub to Agent webhook signature verification',
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
            visibilityTimeout: cdk.Duration.seconds(600),
            deadLetterQueue: {
                queue: agentDlq,
                maxReceiveCount: 3,
            },
        });

        // ==================== SQS (Jira Workflow Queue) ====================
        const jiraWorkflowDlq = new sqs.Queue(this, 'JiraWorkflowDLQ', {
            queueName: 'ai-driven-jira-workflow-dlq.fifo',
            fifo: true,
            retentionPeriod: cdk.Duration.days(14),
        });

        const jiraWorkflowQueue = new sqs.Queue(this, 'JiraWorkflowQueue', {
            queueName: 'ai-driven-jira-workflow.fifo',
            fifo: true,
            contentBasedDeduplication: false,
            visibilityTimeout: cdk.Duration.seconds(120),
            deadLetterQueue: {
                queue: jiraWorkflowDlq,
                maxReceiveCount: 3,
            },
        });

        // ==================== MCP GATEWAY LAMBDA (Node.js) ====================
        const mcpGatewayCodePath = path.join(__dirname, '../../mcp-gateway/dist');
        const mcpGatewayCode = lambda.Code.fromAsset(mcpGatewayCodePath);

        const mcpGatewayRole = new iam.Role(this, 'McpGatewayRole', {
            assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
            ],
        });

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
                JIRA_SECRET_ARN: jiraSecret.secretArn,
                GITHUB_SECRET_ARN: githubSecret.secretArn,
            },
            role: mcpGatewayRole,
            tracing: lambda.Tracing.ACTIVE,
        });

        const mcpGatewayUrl = mcpGatewayHandler.addFunctionUrl({
            authType: lambda.FunctionUrlAuthType.AWS_IAM,
        });

        // ==================== VPC ====================
        const vpc = new ec2.Vpc(this, 'AiDrivenVpc', {
            vpcName: 'ai-driven-vpc',
            maxAzs: 2,
            natGateways: 1,
            subnetConfiguration: [
                {
                    name: 'Public',
                    subnetType: ec2.SubnetType.PUBLIC,
                    cidrMask: 24,
                },
                {
                    name: 'Private',
                    subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS,
                    cidrMask: 24,
                },
            ],
        });

        // ==================== ECS CLUSTER ====================
        const cluster = new ecs.Cluster(this, 'AiDrivenCluster', {
            clusterName: 'ai-driven',
            vpc,
            containerInsights: true,
        });

        // ==================== ECR REPOSITORY ====================
        const ecrRepo = new ecr.Repository(this, 'SpringBootRepo', {
            repositoryName: 'ai-driven/spring-boot-app',
            removalPolicy: cdk.RemovalPolicy.RETAIN,
            lifecycleRules: [
                {
                    maxImageCount: 10,
                    description: 'Keep last 10 images',
                },
            ],
        });

        // ==================== ECS TASK DEFINITION ====================
        const appLogGroup = new logs.LogGroup(this, 'SpringBootLogGroup', {
            logGroupName: '/ecs/ai-driven-spring-boot',
            retention: logs.RetentionDays.TWO_WEEKS,
            removalPolicy: cdk.RemovalPolicy.DESTROY,
        });

        const taskRole = new iam.Role(this, 'SpringBootTaskRole', {
            assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
            description: 'ECS task role for Spring Boot application',
        });

        // Grant task role access to all required AWS resources
        stateTable.grantReadWriteData(taskRole);
        codeContextBucket.grantReadWrite(taskRole);
        auditBucket.grantReadWrite(taskRole);
        agentQueue.grantSendMessages(taskRole);
        agentQueue.grantConsumeMessages(taskRole);
        jiraWorkflowQueue.grantSendMessages(taskRole);
        jiraWorkflowQueue.grantConsumeMessages(taskRole);
        claudeApiKeySecret.grantRead(taskRole);
        bitbucketSecret.grantRead(taskRole);
        jiraSecret.grantRead(taskRole);
        githubSecret.grantRead(taskRole);
        jiraWebhookTokenSecret.grantRead(taskRole);
        githubAgentWebhookSecret.grantRead(taskRole);

        // Grant MCP Gateway invocation (IAM auth on Function URL)
        mcpGatewayHandler.grantInvoke(taskRole);
        taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
            actions: ['lambda:InvokeFunctionUrl'],
            resources: [mcpGatewayHandler.functionArn],
        }));

        const executionRole = new iam.Role(this, 'SpringBootExecutionRole', {
            assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
            managedPolicies: [
                iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
            ],
        });

        const taskDefinition = new ecs.FargateTaskDefinition(this, 'SpringBootTaskDef', {
            family: 'ai-driven-spring-boot',
            cpu: 1024,        // 1 vCPU
            memoryLimitMiB: 2048,  // 2 GB
            taskRole,
            executionRole,
        });

        const container = taskDefinition.addContainer('spring-boot', {
            image: ecs.ContainerImage.fromEcrRepository(ecrRepo, 'latest'),
            logging: ecs.LogDriver.awsLogs({
                logGroup: appLogGroup,
                streamPrefix: 'app',
            }),
            environment: {
                // Core infrastructure
                DYNAMODB_TABLE_NAME: stateTable.tableName,
                CODE_CONTEXT_BUCKET: codeContextBucket.bucketName,
                AUDIT_BUCKET_NAME: `ai-driven-audit-trail-${this.account}`,
                // Secret ARNs (app fetches values from SM at startup)
                CLAUDE_SECRET_ARN: claudeApiKeySecret.secretArn,
                BITBUCKET_SECRET_ARN: bitbucketSecret.secretArn,
                JIRA_SECRET_ARN: jiraSecret.secretArn,
                GITHUB_SECRET_ARN: githubSecret.secretArn,
                JIRA_WEBHOOK_SECRET_ARN: jiraWebhookTokenSecret.secretArn,
                GITHUB_AGENT_WEBHOOK_SECRET_ARN: githubAgentWebhookSecret.secretArn,
                // Claude AI configuration
                CLAUDE_PROVIDER: 'ANTHROPIC_API',
                CLAUDE_MODEL: 'claude-sonnet-4-6',
                CLAUDE_RESEARCHER_MODEL: 'claude-3-haiku-20240307',
                CLAUDE_MAX_TOKENS: '32768',
                CLAUDE_TEMPERATURE: '0.2',
                BEDROCK_REGION: 'ap-southeast-1',
                // Context limits
                MAX_FILE_SIZE_CHARS: '100000',
                MAX_TOTAL_CONTEXT_CHARS: '3000000',
                MAX_FILE_SIZE_BYTES: '500000',
                MAX_CONTEXT_FOR_CLAUDE: '700000',
                CONTEXT_MODE: 'FULL_REPO',
                // Source control
                BRANCH_PREFIX: 'ai/',
                DEFAULT_PLATFORM: 'GITHUB',
                DEFAULT_WORKSPACE: 'AirdropToTheMoon',
                DEFAULT_REPO: 'ai-driven',
                JIRA_APP_USER_EMAIL: 'airdroptothemoon1234567890@gmail.com',
                PROMPT_VERSION: 'v1',
                MERGE_WAIT_TIMEOUT_DAYS: '7',
                // Agent configuration
                AGENT_ENABLED: 'true',
                AGENT_TRIGGER_PREFIX: '@ai',
                AGENT_MAX_TOOL_TURNS: '10',
                AGENT_MAX_WALL_CLOCK_SECONDS: '720',
                AGENT_QUEUE_URL: agentQueue.queueUrl,
                AGENT_GUARDRAILS_ENABLED: 'true',
                AGENT_COST_BUDGET_PER_TICKET: '200000',
                AGENT_CLASSIFIER_USE_LLM: 'false',
                AGENT_MENTION_KEYWORD: 'ai',
                AGENT_BOT_ACCOUNT_ID: '',
                // MCP Gateway
                MCP_GATEWAY_URL: mcpGatewayUrl.url,
                MCP_GATEWAY_ENABLED: 'true',
                MCP_SERVERS_CONFIG: '[]',
                MCP_ALLOW_STDIO_IN_LAMBDA: 'false',
                // Audit & rate limits
                AUDIT_RETENTION_YEARS: '3',
                MAX_REQUESTS_PER_USER_PER_HOUR: '10',
                MAX_REQUESTS_PER_TICKET_PER_HOUR: '20',
                // Deployment tracking
                DEPLOY_ID: this.node.tryGetContext('deployId') || 'none',
                // Spring Boot profile
                SPRING_PROFILES_ACTIVE: 'prod',
                // JVM tuning for container
                JAVA_TOOL_OPTIONS: '-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC',
            },
            healthCheck: {
                command: ['CMD-SHELL', 'curl -f http://localhost:8080/actuator/health || exit 1'],
                interval: cdk.Duration.seconds(30),
                timeout: cdk.Duration.seconds(5),
                retries: 3,
                startPeriod: cdk.Duration.seconds(60),
            },
        });

        container.addPortMappings({ containerPort: 8080 });

        // ==================== ALB + ECS SERVICE ====================
        const albSg = new ec2.SecurityGroup(this, 'AlbSecurityGroup', {
            vpc,
            description: 'ALB security group - allows inbound HTTPS',
            allowAllOutbound: true,
        });
        albSg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(443), 'Allow HTTPS');
        albSg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(80), 'Allow HTTP (redirects to HTTPS)');

        const serviceSg = new ec2.SecurityGroup(this, 'ServiceSecurityGroup', {
            vpc,
            description: 'ECS service security group',
            allowAllOutbound: true,
        });
        serviceSg.addIngressRule(albSg, ec2.Port.tcp(8080), 'Allow ALB to ECS on port 8080');

        const alb = new elbv2.ApplicationLoadBalancer(this, 'AppAlb', {
            loadBalancerName: 'ai-driven-alb',
            vpc,
            internetFacing: true,
            securityGroup: albSg,
        });

        const targetGroup = new elbv2.ApplicationTargetGroup(this, 'SpringBootTG', {
            targetGroupName: 'ai-driven-spring-boot',
            vpc,
            port: 8080,
            protocol: elbv2.ApplicationProtocol.HTTP,
            targetType: elbv2.TargetType.IP,
            healthCheck: {
                path: '/actuator/health',
                interval: cdk.Duration.seconds(30),
                timeout: cdk.Duration.seconds(5),
                healthyThresholdCount: 2,
                unhealthyThresholdCount: 3,
                healthyHttpCodes: '200',
            },
            deregistrationDelay: cdk.Duration.seconds(30),
        });

        // HTTP listener → redirect to HTTPS (when ACM cert is attached)
        // For now, forward on port 80 until TLS is configured
        const httpListener = alb.addListener('HttpListener', {
            port: 80,
            defaultTargetGroups: [targetGroup],
        });

        const fargateService = new ecs.FargateService(this, 'SpringBootService', {
            serviceName: 'ai-driven-spring-boot',
            cluster,
            taskDefinition,
            desiredCount: 1,
            securityGroups: [serviceSg],
            assignPublicIp: false,
            vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
            circuitBreaker: { rollback: true },
            minHealthyPercent: 100,
            maxHealthyPercent: 200,
            enableExecuteCommand: true, // ECS Exec for debugging
        });

        fargateService.attachToApplicationTargetGroup(targetGroup);

        // Auto-scaling: 1–4 tasks based on CPU utilization
        const scaling = fargateService.autoScaleTaskCount({
            minCapacity: 1,
            maxCapacity: 4,
        });

        scaling.scaleOnCpuUtilization('CpuScaling', {
            targetUtilizationPercent: 70,
            scaleInCooldown: cdk.Duration.seconds(300),
            scaleOutCooldown: cdk.Duration.seconds(60),
        });

        scaling.scaleOnMemoryUtilization('MemoryScaling', {
            targetUtilizationPercent: 80,
            scaleInCooldown: cdk.Duration.seconds(300),
            scaleOutCooldown: cdk.Duration.seconds(60),
        });

        // ==================== API GATEWAY → ALB (Webhook Proxy) ====================
        // API Gateway remains the public-facing entry point for webhooks.
        // It proxies requests to the ALB which routes to Spring Boot.
        const api = new apigateway.RestApi(this, 'WebhookApi', {
            restApiName: 'ai-driven-webhook',
            description: 'Webhook endpoints proxied to Spring Boot on ECS/Fargate',
            deployOptions: {
                stageName: 'prod',
                throttlingRateLimit: 100,
                throttlingBurstLimit: 200,
            },
        });

        // HTTP proxy integration: API Gateway → ALB (internet-facing)
        const albIntegration = (httpMethod: string, endpointPath: string) =>
            new apigateway.Integration({
                type: apigateway.IntegrationType.HTTP_PROXY,
                integrationHttpMethod: httpMethod,
                uri: `http://${alb.loadBalancerDnsName}${endpointPath}`,
            });

        // Jira webhook endpoint
        api.root.addResource('jira-webhook').addMethod(
            'POST',
            albIntegration('POST', '/api/webhooks/jira'),
        );

        // Merge webhook endpoint (Bitbucket/GitHub PR merge callbacks)
        api.root.addResource('merge-webhook').addMethod(
            'POST',
            albIntegration('POST', '/api/webhooks/merge'),
        );

        // Agent webhook endpoint (GitHub/Jira @ai comment processing)
        api.root.addResource('agent-webhook').addMethod(
            'POST',
            albIntegration('POST', '/api/webhooks/agent'),
        );

        // ==================== STEP FUNCTIONS (SQS + DynamoDB Polling) ====================
        // ADR-008: Spring Boot handles the full pipeline internally (fetch ticket,
        // fetch code, invoke Claude, create PR). Step Functions provides orchestration
        // visibility by enqueueing work via SQS and polling status from DynamoDB.

        const retryProps = {
            errors: ['States.TaskFailed', 'States.Timeout'],
            interval: cdk.Duration.seconds(5),
            maxAttempts: 3,
            backoffRate: 2,
        };

        const workflowFailed = new stepfunctions.Fail(this, 'WorkflowFailed', {
            error: 'WorkflowExecutionFailed',
            cause: 'One or more steps failed after retries. Check CloudWatch logs for details.',
        });

        // Step 1: Enqueue workflow task to SQS for Spring Boot processing
        const enqueueWorkflowTask = new tasks.SqsSendMessage(this, 'EnqueueWorkflowTask', {
            queue: jiraWorkflowQueue,
            messageBody: stepfunctions.TaskInput.fromJsonPathAt('$'),
            messageGroupId: 'workflow',
            comment: 'Enqueue workflow task for Spring Boot processing',
        });
        enqueueWorkflowTask.addRetry(retryProps);
        enqueueWorkflowTask.addCatch(workflowFailed, { resultPath: '$.error' });

        // Step 2: Wait for completion (callback pattern via SQS)
        const waitForProcessing = new stepfunctions.Wait(this, 'WaitForProcessing', {
            time: stepfunctions.WaitTime.duration(cdk.Duration.seconds(30)),
            comment: 'Wait for Spring Boot to process the workflow task',
        });

        // Step 3: Check status via ALB health endpoint
        const checkStatus = new tasks.CallAwsService(this, 'CheckWorkflowStatus', {
            service: 'dynamodb',
            action: 'getItem',
            iamResources: [stateTable.tableArn],
            parameters: {
                TableName: stateTable.tableName,
                Key: {
                    PK: { 'S.$': "States.Format('WORKFLOW#{}', $.ticketKey)" },
                    SK: { 'S': 'STATUS' },
                },
            },
            resultPath: '$.statusResult',
            comment: 'Check workflow status in DynamoDB',
        });
        checkStatus.addRetry(retryProps);
        checkStatus.addCatch(workflowFailed, { resultPath: '$.error' });

        const workflowComplete = new stepfunctions.Succeed(this, 'WorkflowComplete', {
            comment: 'Workflow completed successfully',
        });

        // Status check: if complete → succeed, if failed → fail, otherwise → wait and retry
        const statusChoice = new stepfunctions.Choice(this, 'StatusCheck')
            .when(
                stepfunctions.Condition.stringEquals('$.statusResult.Item.status.S', 'COMPLETED'),
                workflowComplete,
            )
            .when(
                stepfunctions.Condition.stringEquals('$.statusResult.Item.status.S', 'FAILED'),
                workflowFailed,
            )
            .otherwise(waitForProcessing);

        // Workflow: Enqueue → Wait → CheckStatus → [StatusCheck]
        const workflowDefinition = enqueueWorkflowTask
            .next(waitForProcessing)
            .next(checkStatus)
            .next(statusChoice);

        const workflowLogGroup = new logs.LogGroup(this, 'WorkflowLogGroup', {
            logGroupName: '/aws/vendedlogs/states/ai-driven-workflow',
            retention: logs.RetentionDays.ONE_WEEK,
            removalPolicy: cdk.RemovalPolicy.DESTROY,
        });

        const stateMachine = new stepfunctions.StateMachine(this, 'WorkflowStateMachine', {
            stateMachineName: 'ai-driven-workflow',
            definitionBody: stepfunctions.DefinitionBody.fromChainable(workflowDefinition),
            timeout: cdk.Duration.days(7),
            tracingEnabled: true,
            logs: {
                destination: workflowLogGroup,
                level: stepfunctions.LogLevel.ALL,
            },
        });

        // Grant state machine access to SQS and DynamoDB
        jiraWorkflowQueue.grantSendMessages(stateMachine);
        stateTable.grantReadData(stateMachine);

        // Spring Boot can start Step Functions executions (for webhook-triggered workflows)
        stateMachine.grantStartExecution(taskRole);
        taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
            actions: ['states:SendTaskSuccess', 'states:SendTaskFailure'],
            resources: [stateMachine.stateMachineArn],
        }));

        // Pass state machine ARN to Spring Boot via env
        container.addEnvironment('STATE_MACHINE_ARN', stateMachine.stateMachineArn);

        // ==================== CLOUDWATCH DASHBOARD ====================
        const dashboard = new cloudwatch.Dashboard(this, 'OperationalDashboard', {
            dashboardName: 'ai-driven-operations',
        });

        // Row 1: ECS Service metrics
        dashboard.addWidgets(
            new cloudwatch.GraphWidget({
                title: 'ECS CPU Utilization',
                width: 12,
                left: [
                    new cloudwatch.Metric({
                        namespace: 'AWS/ECS',
                        metricName: 'CPUUtilization',
                        dimensionsMap: {
                            ClusterName: cluster.clusterName,
                            ServiceName: fargateService.serviceName,
                        },
                        statistic: 'avg',
                        label: 'CPU Avg',
                    }),
                    new cloudwatch.Metric({
                        namespace: 'AWS/ECS',
                        metricName: 'CPUUtilization',
                        dimensionsMap: {
                            ClusterName: cluster.clusterName,
                            ServiceName: fargateService.serviceName,
                        },
                        statistic: 'p95',
                        label: 'CPU P95',
                    }),
                ],
            }),
            new cloudwatch.GraphWidget({
                title: 'ECS Memory Utilization',
                width: 12,
                left: [
                    new cloudwatch.Metric({
                        namespace: 'AWS/ECS',
                        metricName: 'MemoryUtilization',
                        dimensionsMap: {
                            ClusterName: cluster.clusterName,
                            ServiceName: fargateService.serviceName,
                        },
                        statistic: 'avg',
                        label: 'Memory Avg',
                    }),
                    new cloudwatch.Metric({
                        namespace: 'AWS/ECS',
                        metricName: 'MemoryUtilization',
                        dimensionsMap: {
                            ClusterName: cluster.clusterName,
                            ServiceName: fargateService.serviceName,
                        },
                        statistic: 'p95',
                        label: 'Memory P95',
                    }),
                ],
            }),
        );

        // Row 2: ALB metrics
        dashboard.addWidgets(
            new cloudwatch.GraphWidget({
                title: 'ALB Request Count & Latency',
                width: 12,
                left: [
                    alb.metrics.requestCount({ label: 'Requests' }),
                ],
                right: [
                    alb.metrics.targetResponseTime({ label: 'Response Time', statistic: 'avg' }),
                    alb.metrics.targetResponseTime({ label: 'P95 Response Time', statistic: 'p95' }),
                ],
            }),
            new cloudwatch.GraphWidget({
                title: 'ALB HTTP Errors',
                width: 12,
                left: [
                    alb.metrics.httpCodeTarget(elbv2.HttpCodeTarget.TARGET_4XX_COUNT, { label: '4xx' }),
                    alb.metrics.httpCodeTarget(elbv2.HttpCodeTarget.TARGET_5XX_COUNT, { label: '5xx' }),
                    alb.metrics.httpCodeElb(elbv2.HttpCodeElb.ELB_5XX_COUNT, { label: 'ELB 5xx' }),
                ],
            }),
        );

        // Row 3: MCP Gateway Lambda metrics
        dashboard.addWidgets(
            new cloudwatch.GraphWidget({
                title: 'MCP Gateway Invocations & Errors',
                width: 12,
                left: [
                    mcpGatewayHandler.metricInvocations({ label: 'Invocations' }),
                    mcpGatewayHandler.metricErrors({ label: 'Errors' }),
                ],
            }),
            new cloudwatch.GraphWidget({
                title: 'MCP Gateway Duration',
                width: 12,
                left: [
                    mcpGatewayHandler.metricDuration({ label: 'Avg Duration', statistic: 'avg' }),
                    mcpGatewayHandler.metricDuration({ label: 'P95 Duration', statistic: 'p95' }),
                ],
            }),
        );

        // Row 4: Step Functions + DynamoDB
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
                title: 'DynamoDB Read/Write Capacity',
                width: 12,
                left: [
                    stateTable.metricConsumedReadCapacityUnits({ label: 'Read CU' }),
                    stateTable.metricConsumedWriteCapacityUnits({ label: 'Write CU' }),
                ],
            }),
        );

        // Row 5: SQS Queue depths
        dashboard.addWidgets(
            new cloudwatch.GraphWidget({
                title: 'SQS Queue Depth',
                width: 12,
                left: [
                    agentQueue.metricApproximateNumberOfMessagesVisible({ label: 'Agent Queue' }),
                    jiraWorkflowQueue.metricApproximateNumberOfMessagesVisible({ label: 'Workflow Queue' }),
                ],
            }),
            new cloudwatch.GraphWidget({
                title: 'SQS DLQ Messages',
                width: 12,
                left: [
                    agentDlq.metricApproximateNumberOfMessagesVisible({ label: 'Agent DLQ' }),
                    jiraWorkflowDlq.metricApproximateNumberOfMessagesVisible({ label: 'Workflow DLQ' }),
                ],
            }),
        );

        // ==================== SECURITY & OPERATIONAL ALARMS ====================
        const alertsTopic = new sns.Topic(this, 'OperationalAlerts', {
            topicName: 'ai-driven-operational-alerts',
            displayName: 'AI Driven Operational Alerts',
        });

        // Alarm: ECS service unhealthy (no running tasks)
        const ecsUnhealthyAlarm = new cloudwatch.Alarm(this, 'EcsUnhealthyAlarm', {
            alarmName: 'ai-driven-ecs-unhealthy',
            alarmDescription: 'Spring Boot ECS service has zero healthy tasks. Application may be down.',
            metric: new cloudwatch.Metric({
                namespace: 'AWS/ECS',
                metricName: 'RunningTaskCount',
                dimensionsMap: {
                    ClusterName: cluster.clusterName,
                    ServiceName: fargateService.serviceName,
                },
                statistic: 'min',
                period: cdk.Duration.minutes(1),
            }),
            threshold: 1,
            evaluationPeriods: 3,
            comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.BREACHING,
        });
        ecsUnhealthyAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Alarm: High CPU utilization
        const cpuAlarm = new cloudwatch.Alarm(this, 'EcsCpuAlarm', {
            alarmName: 'ai-driven-ecs-high-cpu',
            alarmDescription: 'ECS CPU utilization exceeds 85% - consider scaling or optimizing.',
            metric: new cloudwatch.Metric({
                namespace: 'AWS/ECS',
                metricName: 'CPUUtilization',
                dimensionsMap: {
                    ClusterName: cluster.clusterName,
                    ServiceName: fargateService.serviceName,
                },
                statistic: 'avg',
                period: cdk.Duration.minutes(5),
            }),
            threshold: 85,
            evaluationPeriods: 3,
            comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        });
        cpuAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Alarm: ALB 5xx errors
        const alb5xxAlarm = new cloudwatch.Alarm(this, 'Alb5xxAlarm', {
            alarmName: 'ai-driven-alb-5xx-errors',
            alarmDescription: 'ALB returning 5xx errors. Check Spring Boot application logs.',
            metric: alb.metrics.httpCodeTarget(elbv2.HttpCodeTarget.TARGET_5XX_COUNT, {
                period: cdk.Duration.minutes(5),
            }),
            threshold: 10,
            evaluationPeriods: 2,
            comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        });
        alb5xxAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Alarm: ALB high latency
        const latencyAlarm = new cloudwatch.Alarm(this, 'AlbLatencyAlarm', {
            alarmName: 'ai-driven-alb-high-latency',
            alarmDescription: 'ALB P95 response time exceeds 5 seconds.',
            metric: alb.metrics.targetResponseTime({ statistic: 'p95', period: cdk.Duration.minutes(5) }),
            threshold: 5,
            evaluationPeriods: 3,
            comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        });
        latencyAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Alarm: DLQ messages (dead letters indicate processing failures)
        const dlqAlarm = new cloudwatch.Alarm(this, 'DlqAlarm', {
            alarmName: 'ai-driven-dlq-messages',
            alarmDescription: 'Messages appearing in DLQ - tasks are failing after retries.',
            metric: agentDlq.metricApproximateNumberOfMessagesVisible({
                period: cdk.Duration.minutes(5),
            }),
            threshold: 1,
            evaluationPeriods: 1,
            comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        });
        dlqAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Alarm: Webhook security - token verification skipped (log-based)
        const webhookSecurityFilter = new logs.MetricFilter(this, 'WebhookSecurityFilter', {
            logGroup: appLogGroup,
            metricNamespace: 'AiDriven/WebhookSecurity',
            metricName: 'TokenVerificationSkipped',
            filterPattern: logs.FilterPattern.literal('"webhook token verification skipped"'),
            metricValue: '1',
            defaultValue: 0,
        });

        const webhookSecurityAlarm = new cloudwatch.Alarm(this, 'WebhookSecurityAlarm', {
            alarmName: 'ai-driven-webhook-security',
            alarmDescription:
                'Webhook token verification is being skipped. ' +
                'Check that secrets are properly configured in Secrets Manager.',
            metric: webhookSecurityFilter.metric({ period: cdk.Duration.minutes(5) }),
            threshold: 1,
            evaluationPeriods: 1,
            comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        });
        webhookSecurityAlarm.addAlarmAction(new cwActions.SnsAction(alertsTopic));

        // Dashboard: Alarm status panel
        dashboard.addWidgets(
            new cloudwatch.AlarmStatusWidget({
                title: 'Operational & Security Alarms',
                width: 24,
                alarms: [
                    ecsUnhealthyAlarm,
                    cpuAlarm,
                    alb5xxAlarm,
                    latencyAlarm,
                    dlqAlarm,
                    webhookSecurityAlarm,
                ],
            }),
        );

        // ==================== OUTPUTS ====================
        new cdk.CfnOutput(this, 'ApiUrl', {
            value: api.url,
            description: 'API Gateway base URL (webhook endpoints)',
        });

        new cdk.CfnOutput(this, 'AlbDnsName', {
            value: alb.loadBalancerDnsName,
            description: 'Application Load Balancer DNS name',
        });

        new cdk.CfnOutput(this, 'EcsClusterName', {
            value: cluster.clusterName,
            description: 'ECS Cluster name',
        });

        new cdk.CfnOutput(this, 'EcsServiceName', {
            value: fargateService.serviceName,
            description: 'ECS Service name - use with: aws ecs update-service --force-new-deployment',
        });

        new cdk.CfnOutput(this, 'EcrRepositoryUri', {
            value: ecrRepo.repositoryUri,
            description: 'ECR repository URI - push Docker image here',
        });

        new cdk.CfnOutput(this, 'DynamoDBTableName', {
            value: stateTable.tableName,
            description: 'DynamoDB State Table Name',
        });

        new cdk.CfnOutput(this, 'AgentQueueUrl', {
            value: agentQueue.queueUrl,
            description: 'Agent Tasks SQS FIFO Queue URL',
        });

        new cdk.CfnOutput(this, 'StateMachineArn', {
            value: stateMachine.stateMachineArn,
            description: 'Step Functions workflow state machine ARN',
        });

        new cdk.CfnOutput(this, 'JiraWebhookTokenSecretArn', {
            value: jiraWebhookTokenSecret.secretArn,
            description: 'Jira webhook token Secret ARN',
        });

        new cdk.CfnOutput(this, 'GitHubAgentWebhookSecretArn', {
            value: githubAgentWebhookSecret.secretArn,
            description: 'GitHub agent webhook HMAC secret ARN',
        });

        new cdk.CfnOutput(this, 'AlertsTopicArn', {
            value: alertsTopic.topicArn,
            description: 'SNS topic ARN for operational alerts',
        });

        new cdk.CfnOutput(this, 'McpGatewayUrl', {
            value: mcpGatewayUrl.url,
            description: 'MCP Gateway Lambda Function URL',
        });
    }
}
