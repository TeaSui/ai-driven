# AWS Patterns (CDK TypeScript, Serverless-First)

Follow the target project stack, account, credential source, region, deployment process and security requirements first. CDK TypeScript, Node.js 22/ARM64, `ai-driven` and `ap-southeast-1` are personal defaults for choices left open. Preserve installed dependency versions and existing architecture; these fragments are examples, not an instruction to migrate.

Snippets are illustrative construct/handler fragments, not standalone compiled programs. Supply project imports, bindings, business handlers and tests; validate against installed versions before use. Deployment requires task authorization and a verified resolved target. Never expose credentials or sensitive payloads in logs.

## CDK Project Setup

```typescript
// bin/app.ts
const app = new cdk.App();

const env = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION ?? 'ap-southeast-1', // project/CLI target first
};

new DatabaseStack(app, 'Project-Database', { env, envName: 'prod' });
new ComputeStack(app, 'Project-Compute', { env, envName: 'prod' });
```

```typescript
// Shared stack props interface
interface AppStackProps extends cdk.StackProps {
  envName: 'dev' | 'staging' | 'prod';
}
```

## Lambda Patterns

```typescript
// Standard Lambda construct
const handler = new lambda.Function(this, 'Handler', {
  functionName: `${props.envName}-my-handler`,
  runtime: lambda.Runtime.NODEJS_22_X,
  architecture: lambda.Architecture.ARM_64,     // verify dependency compatibility and measured cost
  handler: 'index.handler',
  code: lambda.Code.fromAsset('src/lambdas/handler', {
    bundling: {
      image: lambda.Runtime.NODEJS_22_X.bundlingImage,
      command: ['bash', '-c', 'npm ci && npm run build && cp -r dist/* /asset-output/'],
    },
  }),
  environment: {
    TABLE_NAME: table.tableName,
    QUEUE_URL: queue.queueUrl,
    // NEVER put secrets here — use SSM/Secrets Manager
  },
  timeout: Duration.seconds(30),
  memorySize: 512,
  tracing: lambda.Tracing.ACTIVE,
  logRetention: logs.RetentionDays.ONE_MONTH,
  deadLetterQueue: dlq,
});

// Grant least-privilege permissions
table.grantReadWriteData(handler);
queue.grantSendMessages(handler);
```

## DynamoDB Patterns

Enable PITR by default and preserve project recovery/retention requirements. Current CDK v2 uses `pointInTimeRecoverySpecification`; the older `pointInTimeRecovery` property is deprecated. Use the property supported by the installed CDK, without an unrelated dependency upgrade. [TableProps](https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_dynamodb.TableProps.html), [PITR specification](https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_dynamodb.PointInTimeRecoverySpecification.html).

```typescript
// Single-table design
const table = new dynamodb.Table(this, 'MainTable', {
  tableName: `${props.envName}-main`,
  partitionKey: { name: 'PK', type: dynamodb.AttributeType.STRING },
  sortKey: { name: 'SK', type: dynamodb.AttributeType.STRING },
  billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
  encryption: dynamodb.TableEncryption.AWS_MANAGED,
  pointInTimeRecoverySpecification: { pointInTimeRecoveryEnabled: true },
  removalPolicy: props.envName === 'prod'
    ? cdk.RemovalPolicy.RETAIN
    : cdk.RemovalPolicy.DESTROY,
  stream: dynamodb.StreamViewType.NEW_AND_OLD_IMAGES, // if using DynamoDB Streams
});

// GSI for alternative access patterns
table.addGlobalSecondaryIndex({
  indexName: 'GSI1',
  partitionKey: { name: 'GSI1PK', type: dynamodb.AttributeType.STRING },
  sortKey: { name: 'GSI1SK', type: dynamodb.AttributeType.STRING },
  projectionType: dynamodb.ProjectionType.ALL,
});
```

**Key design patterns:**
```
// Entity          PK                SK
// User            USER#<userId>     METADATA
// Order           ORDER#<orderId>   METADATA
// User's orders   USER#<userId>     ORDER#<orderId>
// By status (GSI) STATUS#<status>   ORDER#<timestamp>#<orderId>
```

## Step Functions Patterns

Retry/Catch are supported on Task, Parallel and Map states. Add bounded retries for transient errors when effects tolerate replay, and Catch handlers where recovery is required. Handle execution-level failures in the caller/monitoring path; do not attach these fields to every state. [AWS error handling](https://docs.aws.amazon.com/step-functions/latest/dg/concepts-error-handling.html).

```typescript
// Fragments inside a construct/stack; project provides functions, table and log group.
const handleError = new sfn.Fail(this, 'WorkflowFailed');
const validate = new tasks.LambdaInvoke(this, 'Validate', {
  lambdaFunction: validateFn,
  outputPath: '$.Payload',
  retryOnServiceExceptions: true,
});

const processJob = new tasks.LambdaInvoke(this, 'Process', {
  lambdaFunction: processFn,
  outputPath: '$.Payload',
}).addRetry({
  errors: ['Lambda.TooManyRequestsException', 'Lambda.ServiceException'],
  interval: Duration.seconds(2),
  maxAttempts: 3,
  backoffRate: 2,
}).addCatch(handleError, { resultPath: '$.error' });

// SDK integration avoids a Lambda wrapper for this write.
const persist = new tasks.DynamoPutItem(this, 'Persist', {
  table,
  item: {
    PK: tasks.DynamoAttributeValue.fromString(sfn.JsonPath.stringAt('$.id')),
    SK: tasks.DynamoAttributeValue.fromString('METADATA'),
    data: tasks.DynamoAttributeValue.mapFromJsonPath('$.data'),
  },
});

const stateMachine = new sfn.StateMachine(this, 'Pipeline', {
  stateMachineName: `${props.envName}-pipeline`,
  stateMachineType: sfn.StateMachineType.EXPRESS, // select for the project's duration/delivery needs
  definitionBody: sfn.DefinitionBody.fromChainable(validate.next(processJob).next(persist)),
  tracingEnabled: true,
  logs: {
    destination: logGroup,
    level: sfn.LogLevel.ERROR,
    includeExecutionData: false, // payloads may contain PII/secrets
  },
});
```

## SQS + Lambda Event Source

The example uses FIFO: keep batches at most 10 and omit `maxBatchingWindow`, which FIFO does not support. Configure the source queue DLQ/redrive policy, implement partial batch responses and make business effects idempotent; SQS event source mappings invoke Lambda synchronously. For FIFO failure responses, preserve ordering by returning failed and unprocessed messages after the first failure. [SQS mapping parameters](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-parameters.html), [AWS SQS handling](https://docs.aws.amazon.com/lambda/latest/dg/with-sqs.html).

```typescript
// FIFO source queue and DLQ must use compatible queue types.
const sourceDlq = new sqs.Queue(this, 'SourceDlq', {
  queueName: `${props.envName}-jobs-dlq.fifo`,
  fifo: true,
});
const queue = new sqs.Queue(this, 'Queue', {
  queueName: `${props.envName}-jobs.fifo`,
  fifo: true,
  contentBasedDeduplication: true,
  visibilityTimeout: Duration.seconds(30 * 6), // 6x Lambda timeout
  deadLetterQueue: {
    queue: sourceDlq,
    maxReceiveCount: 3,
  },
});

// Event source mapping
handler.addEventSource(new lambdaEventSources.SqsEventSource(queue, {
  batchSize: 10,
  reportBatchItemFailures: true, // partial batch response
}));
```

## API Gateway + Lambda

```typescript
const api = new apigateway.RestApi(this, 'Api', {
  restApiName: `${props.envName}-api`,
  defaultCorsPreflightOptions: {
    allowOrigins: projectAllowedOrigins, // explicit approved origins supplied by the project
    allowMethods: ['GET'], // expand only to required methods
  },
  deployOptions: {
    tracingEnabled: true,
    metricsEnabled: true,
    loggingLevel: apigateway.MethodLoggingLevel.INFO,
    throttlingRateLimit: 1000,
    throttlingBurstLimit: 500,
  },
});

const integration = new apigateway.LambdaIntegration(handler, {
  proxy: true,
  timeout: Duration.seconds(29), // example value; verify the API type/account quota
});

const users = api.root.addResource('users');
users.addMethod('GET', integration, {
  authorizationType: apigateway.AuthorizationType.COGNITO,
  authorizer: cognitoAuthorizer,
});
```

## EventBridge Patterns

```typescript
// Rule to trigger Step Functions
new events.Rule(this, 'TriggerPipeline', {
  schedule: events.Schedule.rate(Duration.minutes(5)),
  targets: [
    new eventsTargets.SfnStateMachine(stateMachine, {
      input: events.RuleTargetInput.fromObject({ source: 'scheduled' }),
    }),
  ],
});

// Event bus for domain events
const bus = new events.EventBus(this, 'DomainBus', {
  eventBusName: `${props.envName}-domain`,
});

new events.Rule(this, 'OnOrderCreated', {
  eventBus: bus,
  eventPattern: {
    source: ['com.tyme.orders'],
    detailType: ['OrderCreated'],
  },
  targets: [new eventsTargets.LambdaFunction(processOrderFn)],
});
```

## Observability

```typescript
// Lambda structured logging (PowerTools)
import { Logger } from '@aws-lambda-powertools/logger';
import type { Context, SQSEvent, SQSBatchResponse } from 'aws-lambda';
const logger = new Logger({ serviceName: 'order-processor' });

export const handleBatch = async (event: SQSEvent, context: Context): Promise<SQSBatchResponse> => {
  logger.addContext(context);
  logger.info('Processing batch', { batchSize: event.Records.length });
  // Project processor handles persistence/idempotency and partial failures; never log raw records.
  return processBatch(event);
};

// CloudWatch Alarms
const errorAlarm = handler.metricErrors({ period: Duration.minutes(5) })
  .createAlarm(this, 'LambdaErrors', {
    threshold: 5,
    evaluationPeriods: 2,
    treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    alarmDescription: `${props.envName} handler error rate`,
  });

// Dashboard
new cloudwatch.Dashboard(this, 'Dashboard', {
  dashboardName: `${props.envName}-operations`,
  widgets: [[
    new cloudwatch.GraphWidget({ title: 'Lambda Errors', left: [handler.metricErrors()] }),
    new cloudwatch.GraphWidget({ title: 'DynamoDB Throttles', left: [table.metricThrottledRequests()] }),
  ]],
});
```

## IAM Least Privilege Patterns

```typescript
// Custom policy for specific actions only
handler.addToRolePolicy(new iam.PolicyStatement({
  effect: iam.Effect.ALLOW,
  actions: [
    'ssm:GetParameter',
    'ssm:GetParameters',
  ],
  resources: [
    `arn:${this.partition}:ssm:${this.region}:${this.account}:parameter/${props.envName}/service-name/*`,
  ],
}));

// Secrets Manager access
const secret = secretsmanager.Secret.fromSecretNameV2(this, 'ApiSecret', `${props.envName}/api-key`);
secret.grantRead(handler);
handler.addEnvironment('API_SECRET_ID', secret.secretName); // identifier only, never secretValue
```

`fromSecretNameV2` imports a reference; it does not fetch a value. Pass an identifier and retrieve/cache the value at runtime through the approved SDK/extension, with scoped read/KMS access and no secret logging. [CDK Secret](https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_secretsmanager.Secret.html), [runtime retrieval](https://docs.aws.amazon.com/secretsmanager/latest/userguide/retrieving-secrets_lambda.html).

## Cost Optimization

Estimate cost from current regional pricing, request sizes, frequency, duration, utilization and retention. These are evaluation candidates, not fixed savings guarantees:

| Pattern | Evaluate |
|---------|----------|
| ARM64 Lambda | Dependency compatibility, measured duration and regional compute pricing |
| DynamoDB on-demand/provisioned | Read/write units, utilization, storage, backups and streams; no universal daily-write crossover |
| Express/Standard Step Functions | Required execution/delivery semantics, duration, state transitions and logs |
| Lambda Layers | Packaging/deployment needs; no automatic runtime-cost saving |
| S3 lifecycle/Intelligent-Tiering | Access patterns, minimum durations and monitoring/retrieval charges |
| API caching | Cacheability, tenant/auth isolation, hit ratio and cache costs |

## Deployment Workflow

Resolve credentials/account/region through the project process first; do not replace CI roles or SSO with a personal profile. Use existing authorization for the requested deployment scope. The following are examples to adapt, not commands to run for content validation:

```bash
: "${DEPLOY_STACK:?Set the project-authorized stack name}"
: "${AWS_REGION:?Set the verified project region}"

npx cdk diff "$DEPLOY_STACK"
# Deploy only when the task authorizes this reviewed target; preserve native approval checks.
npx cdk deploy "$DEPLOY_STACK"
aws cloudformation describe-stacks --stack-name "$DEPLOY_STACK" --region "$AWS_REGION"
```

Use the project-approved redacted log/diagnostic workflow; capture status, identifiers and counts rather than dumping request payloads or secrets.

## Resource Tagging (Mandatory)

```typescript
// Apply to entire app
cdk.Tags.of(app).add('Project', projectName); // project-designated identifier
cdk.Tags.of(app).add('ManagedBy', 'cdk');

// Stack-level tags
cdk.Tags.of(this).add('Environment', props.envName);
cdk.Tags.of(this).add('Stack', this.stackName);
```
