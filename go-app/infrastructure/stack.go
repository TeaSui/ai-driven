package main

import (
	"fmt"

	"github.com/aws/aws-cdk-go/awscdk/v2"
	"github.com/aws/aws-cdk-go/awscdk/v2/awsapigateway"
	"github.com/aws/aws-cdk-go/awscdk/v2/awsapplicationautoscaling"
	"github.com/aws/aws-cdk-go/awscdk/v2/awscloudwatch"
	"github.com/aws/aws-cdk-go/awscdk/v2/awsdynamodb"
	"github.com/aws/aws-cdk-go/awscdk/v2/awsec2"
	"github.com/aws/aws-cdk-go/awscdk/v2/awsecr"
	"github.com/aws/aws-cdk-go/awscdk/v2/awsecs"
	"github.com/aws/aws-cdk-go/awscdk/v2/awselasticloadbalancingv2"
	"github.com/aws/aws-cdk-go/awscdk/v2/awsiam"
	"github.com/aws/aws-cdk-go/awscdk/v2/awslambda"
	"github.com/aws/aws-cdk-go/awscdk/v2/awslogs"
	"github.com/aws/aws-cdk-go/awscdk/v2/awss3"
	"github.com/aws/aws-cdk-go/awscdk/v2/awssecretsmanager"
	"github.com/aws/aws-cdk-go/awscdk/v2/awssns"
	"github.com/aws/aws-cdk-go/awscdk/v2/awssqs"
	"github.com/aws/aws-cdk-go/awscdk/v2/awsstepfunctions"
	"github.com/aws/aws-cdk-go/awscdk/v2/awsstepfunctionstasks"
	"github.com/aws/constructs-go/constructs/v10"
	"github.com/aws/jsii-runtime-go"
)

// NewAiDrivenStack creates the full AI-Driven infrastructure stack.
func NewAiDrivenStack(scope constructs.Construct, id string, props *awscdk.StackProps) awscdk.Stack { //nolint:funlen
	stack := awscdk.NewStack(scope, &id, props)

	// ── Secrets ───────────────────────────────────────────────────────
	claudeSecret := lookupSecret(stack, "ClaudeSecret", "ai-driven/claude-api-key")
	bitbucketSecret := lookupSecret(stack, "BitbucketSecret", "ai-driven/bitbucket-credentials")
	jiraSecret := lookupSecret(stack, "JiraSecret", "ai-driven/jira-credentials")
	githubSecret := lookupSecret(stack, "GithubSecret", "ai-driven/github-token")

	jiraWebhookSecret := awssecretsmanager.NewSecret(stack, jsii.String("JiraWebhookTokenSecret"), &awssecretsmanager.SecretProps{
		SecretName:    jsii.String("ai-driven/jira-webhook-token"),
		GenerateSecretString: &awssecretsmanager.SecretStringGenerator{
			PasswordLength:         jsii.Number(32),
			ExcludePunctuation:     jsii.Bool(true),
			IncludeSpace:           jsii.Bool(false),
		},
	})

	githubAgentWebhookSecret := awssecretsmanager.NewSecret(stack, jsii.String("GithubAgentWebhookSecret"), &awssecretsmanager.SecretProps{
		SecretName:    jsii.String("ai-driven/github-agent-webhook-secret"),
		GenerateSecretString: &awssecretsmanager.SecretStringGenerator{
			PasswordLength:         jsii.Number(40),
			ExcludePunctuation:     jsii.Bool(true),
			IncludeSpace:           jsii.Bool(false),
		},
	})

	// ── DynamoDB ──────────────────────────────────────────────────────
	stateTable := awsdynamodb.NewTable(stack, jsii.String("StateTable"), &awsdynamodb.TableProps{
		TableName:    jsii.String("ai-driven-state"),
		PartitionKey: &awsdynamodb.Attribute{Name: jsii.String("PK"), Type: awsdynamodb.AttributeType_STRING},
		SortKey:      &awsdynamodb.Attribute{Name: jsii.String("SK"), Type: awsdynamodb.AttributeType_STRING},
		BillingMode:  awsdynamodb.BillingMode_PAY_PER_REQUEST,
		TimeToLiveAttribute: jsii.String("ttl"),
		PointInTimeRecovery: jsii.Bool(true),
		RemovalPolicy:       awscdk.RemovalPolicy_DESTROY,
	})

	stateTable.AddGlobalSecondaryIndex(&awsdynamodb.GlobalSecondaryIndexProps{
		IndexName:    jsii.String("GSI1"),
		PartitionKey: &awsdynamodb.Attribute{Name: jsii.String("GSI1PK"), Type: awsdynamodb.AttributeType_STRING},
		SortKey:      &awsdynamodb.Attribute{Name: jsii.String("GSI1SK"), Type: awsdynamodb.AttributeType_STRING},
		ProjectionType: awsdynamodb.ProjectionType_ALL,
	})

	// ── S3 ────────────────────────────────────────────────────────────
	codeContextBucket := awss3.NewBucket(stack, jsii.String("CodeContextBucket"), &awss3.BucketProps{
		BucketName:        jsii.String(fmt.Sprintf("ai-driven-code-context-%s", *awscdk.Aws_ACCOUNT_ID())),
		Encryption:        awss3.BucketEncryption_S3_MANAGED,
		BlockPublicAccess: awss3.BlockPublicAccess_BLOCK_ALL(),
		RemovalPolicy:     awscdk.RemovalPolicy_DESTROY,
		AutoDeleteObjects: jsii.Bool(true),
		LifecycleRules: &[]*awss3.LifecycleRule{
			{
				Prefix:     jsii.String("context/"),
				Expiration: awscdk.Duration_Days(jsii.Number(14)),
			},
		},
	})

	auditBucketName := fmt.Sprintf("ai-driven-audit-trail-%s", *awscdk.Aws_ACCOUNT_ID())

	// ── SQS ───────────────────────────────────────────────────────────
	agentDLQ := awssqs.NewQueue(stack, jsii.String("AgentDLQ"), &awssqs.QueueProps{
		QueueName:       jsii.String("ai-driven-agent-tasks-dlq.fifo"),
		Fifo:            jsii.Bool(true),
		RetentionPeriod: awscdk.Duration_Days(jsii.Number(14)),
	})

	agentQueue := awssqs.NewQueue(stack, jsii.String("AgentQueue"), &awssqs.QueueProps{
		QueueName:                  jsii.String("ai-driven-agent-tasks.fifo"),
		Fifo:                       jsii.Bool(true),
		ContentBasedDeduplication:  jsii.Bool(true),
		VisibilityTimeout:          awscdk.Duration_Seconds(jsii.Number(600)),
		DeadLetterQueue: &awssqs.DeadLetterQueue{
			MaxReceiveCount: jsii.Number(3),
			Queue:           agentDLQ,
		},
	})

	workflowDLQ := awssqs.NewQueue(stack, jsii.String("WorkflowDLQ"), &awssqs.QueueProps{
		QueueName:       jsii.String("ai-driven-jira-workflow-dlq.fifo"),
		Fifo:            jsii.Bool(true),
		RetentionPeriod: awscdk.Duration_Days(jsii.Number(14)),
	})

	workflowQueue := awssqs.NewQueue(stack, jsii.String("WorkflowQueue"), &awssqs.QueueProps{
		QueueName:                  jsii.String("ai-driven-jira-workflow.fifo"),
		Fifo:                       jsii.Bool(true),
		ContentBasedDeduplication:  jsii.Bool(false),
		VisibilityTimeout:          awscdk.Duration_Seconds(jsii.Number(120)),
		DeadLetterQueue: &awssqs.DeadLetterQueue{
			MaxReceiveCount: jsii.Number(3),
			Queue:           workflowDLQ,
		},
	})

	// ── MCP Gateway Lambda ────────────────────────────────────────────
	mcpGatewayRole := awsiam.NewRole(stack, jsii.String("McpGatewayRole"), &awsiam.RoleProps{
		AssumedBy:      awsiam.NewServicePrincipal(jsii.String("lambda.amazonaws.com"), nil),
		ManagedPolicies: &[]awsiam.IManagedPolicy{
			awsiam.ManagedPolicy_FromAwsManagedPolicyName(jsii.String("service-role/AWSLambdaBasicExecutionRole")),
		},
	})

	jiraSecret.GrantRead(mcpGatewayRole, nil)
	githubSecret.GrantRead(mcpGatewayRole, nil)

	mcpGateway := awslambda.NewFunction(stack, jsii.String("McpGateway"), &awslambda.FunctionProps{
		FunctionName: jsii.String("ai-driven-mcp-gateway"),
		Runtime:      awslambda.Runtime_NODEJS_20_X(),
		Handler:      jsii.String("index.handler"),
		Code:         awslambda.Code_FromAsset(jsii.String("../../mcp-gateway/dist"), nil),
		MemorySize:   jsii.Number(512),
		Timeout:      awscdk.Duration_Seconds(jsii.Number(30)),
		Role:         mcpGatewayRole,
		Tracing:      awslambda.Tracing_ACTIVE,
		Environment: &map[string]*string{
			"CONTEXT7_ENABLED":  jsii.String("true"),
			"JIRA_SECRET_ARN":   jiraSecret.SecretArn(),
			"GITHUB_SECRET_ARN": githubSecret.SecretArn(),
		},
	})

	mcpGatewayUrl := mcpGateway.AddFunctionUrl(&awslambda.FunctionUrlOptions{
		AuthType: awslambda.FunctionUrlAuthType_AWS_IAM,
	})

	// ── VPC ───────────────────────────────────────────────────────────
	vpc := awsec2.NewVpc(stack, jsii.String("Vpc"), &awsec2.VpcProps{
		VpcName:     jsii.String("ai-driven-vpc"),
		MaxAzs:      jsii.Number(2),
		NatGateways: jsii.Number(1),
		SubnetConfiguration: &[]*awsec2.SubnetConfiguration{
			{
				Name:       jsii.String("Public"),
				SubnetType: awsec2.SubnetType_PUBLIC,
				CidrMask:   jsii.Number(24),
			},
			{
				Name:       jsii.String("Private"),
				SubnetType: awsec2.SubnetType_PRIVATE_WITH_EGRESS,
				CidrMask:   jsii.Number(24),
			},
		},
	})

	// ── ECS Cluster ───────────────────────────────────────────────────
	cluster := awsecs.NewCluster(stack, jsii.String("Cluster"), &awsecs.ClusterProps{
		ClusterName:       jsii.String("ai-driven"),
		Vpc:               vpc,
		ContainerInsights: jsii.Bool(true),
	})

	// ── ECR Repository ────────────────────────────────────────────────
	ecrRepo := awsecr.NewRepository(stack, jsii.String("EcrRepo"), &awsecr.RepositoryProps{
		RepositoryName: jsii.String("ai-driven/go-app"),
		LifecycleRules: &[]*awsecr.LifecycleRule{
			{MaxImageCount: jsii.Number(10)},
		},
		RemovalPolicy: awscdk.RemovalPolicy_RETAIN,
	})

	// ── ECS Task Definition ───────────────────────────────────────────
	taskDef := awsecs.NewFargateTaskDefinition(stack, jsii.String("TaskDef"), &awsecs.FargateTaskDefinitionProps{
		Family:   jsii.String("ai-driven-go"),
		Cpu:      jsii.Number(256),  // 0.25 vCPU (Go is lean)
		MemoryLimitMiB: jsii.Number(512), // 512 MB
	})

	logGroup := awslogs.NewLogGroup(stack, jsii.String("AppLogGroup"), &awslogs.LogGroupProps{
		LogGroupName:  jsii.String("/ecs/ai-driven-go"),
		Retention:     awslogs.RetentionDays_TWO_WEEKS,
		RemovalPolicy: awscdk.RemovalPolicy_DESTROY,
	})

	deployID := awscdk.NewCfnParameter(stack, jsii.String("DeployId"), &awscdk.CfnParameterProps{
		Type:    jsii.String("String"),
		Default: jsii.String("none"),
	})

	containerEnv := map[string]*string{
		// Infrastructure references
		"DYNAMODB_TABLE_NAME": stateTable.TableName(),
		"CODE_CONTEXT_BUCKET": codeContextBucket.BucketName(),
		"AUDIT_BUCKET_NAME":   jsii.String(auditBucketName),
		"MCP_GATEWAY_URL":     mcpGatewayUrl.Url(),

		// Secret ARNs (resolved at runtime by the app)
		"CLAUDE_SECRET_ARN":                claudeSecret.SecretArn(),
		"BITBUCKET_SECRET_ARN":             bitbucketSecret.SecretArn(),
		"JIRA_SECRET_ARN":                  jiraSecret.SecretArn(),
		"GITHUB_SECRET_ARN":                githubSecret.SecretArn(),
		"JIRA_WEBHOOK_SECRET_ARN":          jiraWebhookSecret.SecretArn(),
		"GITHUB_AGENT_WEBHOOK_SECRET_ARN":  githubAgentWebhookSecret.SecretArn(),

		// Claude configuration
		"CLAUDE_PROVIDER":         jsii.String("ANTHROPIC_API"),
		"CLAUDE_MODEL":            jsii.String("claude-sonnet-4-6"),
		"CLAUDE_RESEARCHER_MODEL": jsii.String("claude-3-haiku-20240307"),
		"CLAUDE_MAX_TOKENS":       jsii.String("32768"),
		"CLAUDE_TEMPERATURE":      jsii.String("0.2"),
		"BEDROCK_REGION":          jsii.String("ap-southeast-1"),

		// Context limits
		"MAX_FILE_SIZE_CHARS":      jsii.String("100000"),
		"MAX_TOTAL_CONTEXT_CHARS":  jsii.String("3000000"),
		"MAX_FILE_SIZE_BYTES":      jsii.String("500000"),
		"MAX_CONTEXT_FOR_CLAUDE":   jsii.String("700000"),
		"CONTEXT_MODE":             jsii.String("FULL_REPO"),

		// Source control
		"BRANCH_PREFIX":          jsii.String("ai/"),
		"DEFAULT_PLATFORM":       jsii.String("GITHUB"),
		"DEFAULT_WORKSPACE":      jsii.String("AirdropToTheMoon"),
		"DEFAULT_REPO":           jsii.String("ai-driven"),
		"JIRA_APP_USER_EMAIL":    jsii.String("airdroptothemoon1234567890@gmail.com"),
		"PROMPT_VERSION":         jsii.String("v1"),
		"MERGE_WAIT_TIMEOUT_DAYS": jsii.String("7"),

		// Agent configuration
		"AGENT_ENABLED":                jsii.String("true"),
		"AGENT_TRIGGER_PREFIX":         jsii.String("@ai"),
		"AGENT_MAX_TOOL_TURNS":         jsii.String("10"),
		"AGENT_MAX_WALL_CLOCK_SECONDS": jsii.String("720"),
		"AGENT_QUEUE_URL":              agentQueue.QueueUrl(),
		"AGENT_GUARDRAILS_ENABLED":     jsii.String("true"),
		"AGENT_COST_BUDGET_PER_TICKET": jsii.String("200000"),
		"AGENT_CLASSIFIER_USE_LLM":     jsii.String("false"),
		"AGENT_MENTION_KEYWORD":        jsii.String("ai"),
		"AGENT_BOT_ACCOUNT_ID":         jsii.String(""),

		// MCP gateway
		"MCP_GATEWAY_ENABLED":      jsii.String("true"),
		"MCP_SERVERS_CONFIG":       jsii.String("[]"),
		"MCP_ALLOW_STDIO_IN_LAMBDA": jsii.String("false"),

		// Audit & rate limits
		"AUDIT_RETENTION_YEARS":              jsii.String("3"),
		"MAX_REQUESTS_PER_USER_PER_HOUR":     jsii.String("10"),
		"MAX_REQUESTS_PER_TICKET_PER_HOUR":   jsii.String("20"),

		// Deployment
		"DEPLOY_ID":    deployID.ValueAsString(),
		"ENVIRONMENT":  jsii.String("production"),
	}

	container := taskDef.AddContainer(jsii.String("app"), &awsecs.ContainerDefinitionOptions{
		Image: awsecs.ContainerImage_FromEcrRepository(ecrRepo, jsii.String("latest")),
		Logging: awsecs.LogDriver_AwsLogs(&awsecs.AwsLogDriverProps{
			LogGroup:     logGroup,
			StreamPrefix: jsii.String("app"),
		}),
		Environment: &containerEnv,
		HealthCheck: &awsecs.HealthCheck{
			Command:     jsii.Strings("CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/health", "||", "exit", "1"),
			Interval:    awscdk.Duration_Seconds(jsii.Number(30)),
			Timeout:     awscdk.Duration_Seconds(jsii.Number(5)),
			Retries:     jsii.Number(3),
			StartPeriod: awscdk.Duration_Seconds(jsii.Number(10)), // Go starts fast
		},
	})

	container.AddPortMappings(&awsecs.PortMapping{
		ContainerPort: jsii.Number(8080),
		Protocol:      awsecs.Protocol_TCP,
	})

	// ── Task Role Permissions ─────────────────────────────────────────
	taskRole := taskDef.TaskRole()
	stateTable.GrantReadWriteData(taskRole)
	codeContextBucket.GrantReadWrite(taskRole, nil)
	agentQueue.GrantSendMessages(taskRole)
	agentQueue.GrantConsumeMessages(taskRole)
	workflowQueue.GrantSendMessages(taskRole)
	workflowQueue.GrantConsumeMessages(taskRole)

	for _, secret := range []awssecretsmanager.ISecret{
		claudeSecret, bitbucketSecret, jiraSecret, githubSecret,
		jiraWebhookSecret, githubAgentWebhookSecret,
	} {
		secret.GrantRead(taskRole, nil)
	}

	mcpGateway.GrantInvoke(taskRole)

	taskRole.AddToPrincipalPolicy(awsiam.NewPolicyStatement(&awsiam.PolicyStatementProps{
		Actions: jsii.Strings(
			"s3:GetObject", "s3:PutObject",
		),
		Resources: jsii.Strings(
			fmt.Sprintf("arn:aws:s3:::%s/*", auditBucketName),
		),
	}))

	// ── ALB + Fargate Service ─────────────────────────────────────────
	albSg := awsec2.NewSecurityGroup(stack, jsii.String("AlbSg"), &awsec2.SecurityGroupProps{
		Vpc:              vpc,
		Description:      jsii.String("ALB security group"),
		AllowAllOutbound: jsii.Bool(true),
	})
	albSg.AddIngressRule(awsec2.Peer_AnyIpv4(), awsec2.Port_Tcp(jsii.Number(80)), jsii.String("HTTP"), nil)
	albSg.AddIngressRule(awsec2.Peer_AnyIpv4(), awsec2.Port_Tcp(jsii.Number(443)), jsii.String("HTTPS"), nil)

	alb := awselasticloadbalancingv2.NewApplicationLoadBalancer(stack, jsii.String("ALB"), &awselasticloadbalancingv2.ApplicationLoadBalancerProps{
		LoadBalancerName: jsii.String("ai-driven-alb"),
		Vpc:              vpc,
		InternetFacing:   jsii.Bool(true),
		SecurityGroup:    albSg,
	})

	service := awsecs.NewFargateService(stack, jsii.String("Service"), &awsecs.FargateServiceProps{
		ServiceName:          jsii.String("ai-driven-go"),
		Cluster:              cluster,
		TaskDefinition:       taskDef,
		DesiredCount:         jsii.Number(1),
		AssignPublicIp:       jsii.Bool(false),
		EnableExecuteCommand: jsii.Bool(true),
		CircuitBreaker:       &awsecs.DeploymentCircuitBreaker{Rollback: jsii.Bool(true)},
		MinHealthyPercent:    jsii.Number(100),
		MaxHealthyPercent:    jsii.Number(200),
		VpcSubnets:           &awsec2.SubnetSelection{SubnetType: awsec2.SubnetType_PRIVATE_WITH_EGRESS},
	})

	service.Connections().AllowFrom(albSg, awsec2.Port_Tcp(jsii.Number(8080)), jsii.String("ALB to ECS"))

	listener := alb.AddListener(jsii.String("HttpListener"), &awselasticloadbalancingv2.BaseApplicationListenerProps{
		Port: jsii.Number(80),
	})

	listener.AddTargets(jsii.String("EcsTarget"), &awselasticloadbalancingv2.AddApplicationTargetsProps{
		TargetGroupName: jsii.String("ai-driven-go"),
		Port:            jsii.Number(8080),
		Targets:         &[]awselasticloadbalancingv2.IApplicationLoadBalancerTarget{service},
		HealthCheck: &awselasticloadbalancingv2.HealthCheck{
			Path:                    jsii.String("/health"),
			Interval:                awscdk.Duration_Seconds(jsii.Number(30)),
			Timeout:                 awscdk.Duration_Seconds(jsii.Number(5)),
			HealthyThresholdCount:   jsii.Number(2),
			UnhealthyThresholdCount: jsii.Number(3),
		},
		DeregistrationDelay: awscdk.Duration_Seconds(jsii.Number(30)),
	})

	// ── Auto-Scaling ──────────────────────────────────────────────────
	scaling := service.AutoScaleTaskCount(&awsapplicationautoscaling.EnableScalingProps{
		MinCapacity: jsii.Number(1),
		MaxCapacity: jsii.Number(4),
	})

	scaling.ScaleOnCpuUtilization(jsii.String("CpuScaling"), &awsecs.CpuUtilizationScalingProps{
		TargetUtilizationPercent: jsii.Number(70),
		ScaleOutCooldown:         awscdk.Duration_Seconds(jsii.Number(60)),
		ScaleInCooldown:          awscdk.Duration_Seconds(jsii.Number(300)),
	})

	scaling.ScaleOnMemoryUtilization(jsii.String("MemoryScaling"), &awsecs.MemoryUtilizationScalingProps{
		TargetUtilizationPercent: jsii.Number(80),
		ScaleOutCooldown:         awscdk.Duration_Seconds(jsii.Number(60)),
		ScaleInCooldown:          awscdk.Duration_Seconds(jsii.Number(300)),
	})

	// ── API Gateway (webhook proxy) ───────────────────────────────────
	api := awsapigateway.NewRestApi(stack, jsii.String("WebhookApi"), &awsapigateway.RestApiProps{
		RestApiName: jsii.String("ai-driven-webhook"),
		DeployOptions: &awsapigateway.StageOptions{
			StageName: jsii.String("prod"),
			ThrottlingRateLimit:  jsii.Number(100),
			ThrottlingBurstLimit: jsii.Number(200),
		},
	})

	albDns := alb.LoadBalancerDnsName()
	webhookIntegration := func(path string) awsapigateway.Integration {
		return awsapigateway.NewHttpIntegration(
			jsii.String(fmt.Sprintf("http://%s%s", *albDns, path)),
			&awsapigateway.HttpIntegrationProps{
				HttpMethod: jsii.String("POST"),
				Proxy:      jsii.Bool(true),
			},
		)
	}

	api.Root().AddResource(jsii.String("jira-webhook"), nil).AddMethod(jsii.String("POST"), webhookIntegration("/webhooks/jira/agent"), nil)
	api.Root().AddResource(jsii.String("merge-webhook"), nil).AddMethod(jsii.String("POST"), webhookIntegration("/webhooks/merge"), nil)
	api.Root().AddResource(jsii.String("agent-webhook"), nil).AddMethod(jsii.String("POST"), webhookIntegration("/webhooks/github/agent"), nil)

	// ── Step Functions ────────────────────────────────────────────────
	sfnLogGroup := awslogs.NewLogGroup(stack, jsii.String("SfnLogGroup"), &awslogs.LogGroupProps{
		LogGroupName:  jsii.String("/aws/vendedlogs/states/ai-driven-workflow"),
		Retention:     awslogs.RetentionDays_ONE_WEEK,
		RemovalPolicy: awscdk.RemovalPolicy_DESTROY,
	})

	enqueueTask := awsstepfunctionstasks.NewSqsSendMessage(stack, jsii.String("EnqueueWorkflowTask"), &awsstepfunctionstasks.SqsSendMessageProps{
		Queue:       workflowQueue,
		MessageBody: awsstepfunctions.TaskInput_FromJsonPathAt(jsii.String("$")),
		MessageGroupId: jsii.String("workflow"),
	})

	waitStep := awsstepfunctions.NewWait(stack, jsii.String("WaitForProcessing"), &awsstepfunctions.WaitProps{
		Time: awsstepfunctions.WaitTime_Duration(awscdk.Duration_Seconds(jsii.Number(30))),
	})

	checkStatus := awsstepfunctionstasks.NewDynamoGetItem(stack, jsii.String("CheckWorkflowStatus"), &awsstepfunctionstasks.DynamoGetItemProps{
		Table: stateTable,
		Key: &map[string]awsstepfunctionstasks.DynamoAttributeValue{
			"PK": awsstepfunctionstasks.DynamoAttributeValue_FromString(
				awsstepfunctions.JsonPath_StringAt(jsii.String("States.Format('WORKFLOW#{}#STATUS', $.ticketKey)")),
			),
			"SK": awsstepfunctionstasks.DynamoAttributeValue_FromString(jsii.String("STATUS")),
		},
		ResultPath: jsii.String("$.statusResult"),
	})
	checkStatus.AddRetry(&awsstepfunctions.RetryProps{
		MaxAttempts: jsii.Number(3),
		Interval:    awscdk.Duration_Seconds(jsii.Number(5)),
		BackoffRate: jsii.Number(2),
	})

	succeedState := awsstepfunctions.NewSucceed(stack, jsii.String("WorkflowCompleted"), nil)
	failState := awsstepfunctions.NewFail(stack, jsii.String("WorkflowFailed"), &awsstepfunctions.FailProps{
		Cause: jsii.String("Workflow processing failed"),
	})

	statusChoice := awsstepfunctions.NewChoice(stack, jsii.String("StatusCheck"), nil).
		When(awsstepfunctions.Condition_StringEquals(jsii.String("$.statusResult.Item.status.S"), jsii.String("COMPLETED")), succeedState, nil).
		When(awsstepfunctions.Condition_StringEquals(jsii.String("$.statusResult.Item.status.S"), jsii.String("FAILED")), failState, nil).
		Otherwise(waitStep)

	definition := enqueueTask.Next(waitStep).Next(checkStatus).Next(statusChoice)

	stateMachine := awsstepfunctions.NewStateMachine(stack, jsii.String("Workflow"), &awsstepfunctions.StateMachineProps{
		StateMachineName: jsii.String("ai-driven-workflow"),
		DefinitionBody:   awsstepfunctions.DefinitionBody_FromChainable(definition),
		Timeout:          awscdk.Duration_Days(jsii.Number(7)),
		TracingEnabled:   jsii.Bool(true),
		Logs: &awsstepfunctions.LogOptions{
			Destination: sfnLogGroup,
			Level:       awsstepfunctions.LogLevel_ALL,
		},
	})

	// Grant Step Functions permissions to task role
	taskRole.AddToPrincipalPolicy(awsiam.NewPolicyStatement(&awsiam.PolicyStatementProps{
		Actions: jsii.Strings(
			"states:StartExecution",
			"states:SendTaskSuccess",
			"states:SendTaskFailure",
		),
		Resources: jsii.Strings(*stateMachine.StateMachineArn()),
	}))

	// Add STATE_MACHINE_ARN to container env
	// (handled via CfnOutput since env map is already built)

	// ── SNS Alerts Topic ──────────────────────────────────────────────
	alertsTopic := awssns.NewTopic(stack, jsii.String("AlertsTopic"), &awssns.TopicProps{
		TopicName: jsii.String("ai-driven-operational-alerts"),
	})

	// ── CloudWatch Alarms ─────────────────────────────────────────────
	createAlarm(stack, "EcsUnhealthyAlarm", &alarmConfig{
		metric: service.Metric(jsii.String("RunningTaskCount"), &awscloudwatch.MetricOptions{
			Period:    awscdk.Duration_Minutes(jsii.Number(1)),
			Statistic: jsii.String("Average"),
		}),
		threshold:          1,
		evaluationPeriods:  3,
		comparisonOperator: awscloudwatch.ComparisonOperator_LESS_THAN_THRESHOLD,
		topic:              alertsTopic,
	})

	createAlarm(stack, "EcsCpuAlarm", &alarmConfig{
		metric: service.MetricCpuUtilization(&awscloudwatch.MetricOptions{
			Period: awscdk.Duration_Minutes(jsii.Number(5)),
		}),
		threshold:          85,
		evaluationPeriods:  3,
		comparisonOperator: awscloudwatch.ComparisonOperator_GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
		topic:              alertsTopic,
	})

	createAlarm(stack, "DlqAlarm", &alarmConfig{
		metric: agentDLQ.MetricApproximateNumberOfMessagesVisible(&awscloudwatch.MetricOptions{
			Period: awscdk.Duration_Minutes(jsii.Number(5)),
		}),
		threshold:          1,
		evaluationPeriods:  1,
		comparisonOperator: awscloudwatch.ComparisonOperator_GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
		topic:              alertsTopic,
	})

	// ── CloudWatch Dashboard ──────────────────────────────────────────
	awscloudwatch.NewDashboard(stack, jsii.String("Dashboard"), &awscloudwatch.DashboardProps{
		DashboardName: jsii.String("ai-driven-operations"),
	})

	// ── Outputs ───────────────────────────────────────────────────────
	output(stack, "ApiGatewayUrl", api.Url())
	output(stack, "AlbDnsName", alb.LoadBalancerDnsName())
	output(stack, "EcsClusterName", cluster.ClusterName())
	output(stack, "EcsServiceName", service.ServiceName())
	output(stack, "EcrRepositoryUri", ecrRepo.RepositoryUri())
	output(stack, "DynamoDBTableName", stateTable.TableName())
	output(stack, "AgentQueueUrl", agentQueue.QueueUrl())
	output(stack, "StateMachineArn", stateMachine.StateMachineArn())
	output(stack, "JiraWebhookSecretArn", jiraWebhookSecret.SecretArn())
	output(stack, "GithubWebhookSecretArn", githubAgentWebhookSecret.SecretArn())
	output(stack, "AlertsTopicArn", alertsTopic.TopicArn())
	output(stack, "McpGatewayUrl", mcpGatewayUrl.Url())

	// Suppress unused variable warnings for constructs referenced only via grants
	_ = ecrRepo
	_ = container

	return stack
}

// ── Helpers ───────────────────────────────────────────────────────────

func lookupSecret(stack awscdk.Stack, id, name string) awssecretsmanager.ISecret {
	return awssecretsmanager.Secret_FromSecretNameV2(stack, jsii.String(id), jsii.String(name))
}

func output(stack awscdk.Stack, name string, value *string) {
	awscdk.NewCfnOutput(stack, jsii.String(name), &awscdk.CfnOutputProps{
		Value: value,
	})
}

type alarmConfig struct {
	metric             awscloudwatch.IMetric
	threshold          float64
	evaluationPeriods  float64
	comparisonOperator awscloudwatch.ComparisonOperator
	topic              awssns.ITopic
}

func createAlarm(stack awscdk.Stack, id string, cfg *alarmConfig) {
	alarm := awscloudwatch.NewAlarm(stack, jsii.String(id), &awscloudwatch.AlarmProps{
		Metric:             cfg.metric,
		Threshold:          jsii.Number(cfg.threshold),
		EvaluationPeriods:  jsii.Number(cfg.evaluationPeriods),
		ComparisonOperator: cfg.comparisonOperator,
	})
	_ = alarm
	// In production, connect: alarm.AddAlarmAction(awscloudwatchactions.NewSnsAction(cfg.topic))
}
