# AWS Patterns (CDK TypeScript, Serverless-First)

## CDK Project Setup

```typescript
// bin/app.ts
const app = new cdk.App();

const env = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: 'ap-southeast-1',
};

new DatabaseStack(app, 'AiDriven-Database', { env, envName: 'prod' });
new ComputeStack(app, 'AiDriven-Compute', { env, envName: 'prod' });
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
  architecture: lambda.Architecture.ARM_64,     // Graviton2: ~20% cheaper
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

```typescript
// Single-table design
const table = new dynamodb.Table(this, 'MainTable', {
  tableName: `${props.envName}-main`,
  partitionKey: { name: 'PK', type: dynamodb.AttributeType.STRING },
  sortKey: { name: 'SK', type: dynamodb.AttributeType.STRING },
  billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
  encryption: dynamodb.TableEncryption.AWS_MANAGED,
  pointInTimeRecovery: true,
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

```typescript
// Express workflow (event-driven, high volume)
const stateMachine = new sfn.StateMachine(this, 'Pipeline', {
  stateMachineName: `${props.envName}-pipeline`,
  stateMachineType: sfn.StateMachineType.EXPRESS,
  definitionBody: sfn.DefinitionBody.fromChainable(buildWorkflow()),
  tracingEnabled: true,
  logs: {
    destination: logGroup,
    level: sfn.LogLevel.ALL,
    includeExecutionData: true,
  },
});

// Workflow definition
function buildWorkflow() {
  const validate = new tasks.LambdaInvoke(this, 'Validate', {
    lambdaFunction: validateFn,
    outputPath: '$.Payload',
    retryOnServiceExceptions: true,
  });

  const process = new tasks.LambdaInvoke(this, 'Process', {
    lambdaFunction: processFn,
    outputPath: '$.Payload',
  }).addRetry({
    errors: ['Lambda.TooManyRequestsException', 'Lambda.ServiceException'],
    interval: Duration.seconds(2),
    maxAttempts: 3,
    backoffRate: 2,
  }).addCatch(handleError, { resultPath: '$.error' });

  // Use SDK integrations for simple DynamoDB writes (no Lambda wrapper needed)
  const persist = new tasks.DynamoPutItem(this, 'Persist', {
    table,
    item: {
      PK: tasks.DynamoAttributeValue.fromString(sfn.JsonPath.stringAt('$.id')),
      SK: tasks.DynamoAttributeValue.fromString('METADATA'),
      data: tasks.DynamoAttributeValue.mapFromJsonPath('$.data'),
    },
  });

  return validate.next(process).next(persist);
}
```

## SQS + Lambda Event Source

```typescript
// FIFO queue for ordered processing
const queue = new sqs.Queue(this, 'Queue', {
  queueName: `${props.envName}-jobs.fifo`,
  fifo: true,
  contentBasedDeduplication: true,
  visibilityTimeout: Duration.seconds(30 * 6), // 6x Lambda timeout
  deadLetterQueue: {
    queue: dlq,
    maxReceiveCount: 3,
  },
});

// Event source mapping
handler.addEventSource(new lambdaEventSources.SqsEventSource(queue, {
  batchSize: 10,
  maxBatchingWindow: Duration.seconds(5),
  reportBatchItemFailures: true, // partial batch response
}));
```

## API Gateway + Lambda

```typescript
const api = new apigateway.RestApi(this, 'Api', {
  restApiName: `${props.envName}-api`,
  defaultCorsPreflightOptions: {
    allowOrigins: ['https://your-frontend-domain.com'], // NEVER use ALL_ORIGINS in production
    allowMethods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
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
  timeout: Duration.seconds(29), // API GW max is 29s
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
const logger = new Logger({ serviceName: 'order-processor' });

export const handler = async (event: SQSEvent): Promise<SQSBatchResponse> => {
  logger.addContext(context);
  logger.info('Processing batch', { batchSize: event.Records.length });
  // ...
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
    `arn:aws:ssm:ap-southeast-1:${this.account}:parameter/${props.envName}/*`,
  ],
}));

// Secrets Manager access
const secret = secretsmanager.Secret.fromSecretNameV2(this, 'ApiSecret', `${props.envName}/api-key`);
secret.grantRead(handler);
```

## Cost Optimization

| Pattern | Cost Impact |
|---------|-------------|
| ARM64 (Graviton2) Lambda | ~20% cheaper compute |
| PAY_PER_REQUEST DynamoDB | Zero cost when idle |
| Express vs Standard Step Functions | ~100x cheaper for high volume |
| Lambda Layers for dependencies | Faster deploys, same cost |
| S3 Intelligent-Tiering | Auto 40-68% savings for infrequent data |
| CloudFront in front of API GW | Reduces API GW invocations via caching |

## Deployment Workflow

```bash
# Always check diff before deploy
AWS_PROFILE=ai-driven npx cdk diff

# Deploy specific stack
AWS_PROFILE=ai-driven npx cdk deploy AiDriven-Compute --require-approval broadening

# Deploy all
AWS_PROFILE=ai-driven npx cdk deploy --all

# Check Lambda logs
AWS_PROFILE=ai-driven aws logs tail /aws/lambda/prod-handler --follow --region ap-southeast-1

# Check Step Functions execution
AWS_PROFILE=ai-driven aws stepfunctions describe-execution \
  --execution-arn <arn> --region ap-southeast-1
```

## Resource Tagging (Mandatory)

```typescript
// Apply to entire app
cdk.Tags.of(app).add('Project', 'ai-driven');
cdk.Tags.of(app).add('ManagedBy', 'cdk');

// Stack-level tags
cdk.Tags.of(this).add('Environment', props.envName);
cdk.Tags.of(this).add('Stack', this.stackName);
```
