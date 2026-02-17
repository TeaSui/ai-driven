import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { Construct } from 'constructs';
import { TenantConfig } from '../config/tenant-config';

/**
 * Shared infrastructure resources used by all services.
 * Deployed once per tenant. Other service constructs reference these outputs.
 *
 * Resources:
 *   - DynamoDB state table (single-table design)
 *   - S3 code context bucket
 *   - Secrets Manager entries (Claude, Jira, source control)
 *   - SQS FIFO queue for agent tasks (optional, only if agent enabled)
 */
export interface SharedInfraProps {
    readonly tenantConfig: TenantConfig;
}

export class SharedInfraConstruct extends Construct {
    public readonly stateTable: dynamodb.Table;
    public readonly codeContextBucket: s3.Bucket;
    public readonly claudeApiKeySecret: secretsmanager.Secret;
    public readonly jiraSecret: secretsmanager.Secret;
    public readonly bitbucketSecret: secretsmanager.Secret | undefined;
    public readonly githubSecret: secretsmanager.Secret | undefined;
    public readonly agentQueue: sqs.Queue | undefined;
    public readonly agentDlq: sqs.Queue | undefined;

    constructor(scope: Construct, id: string, props: SharedInfraProps) {
        super(scope, id);

        const { tenantConfig } = props;
        const prefix = tenantConfig.resourcePrefix;

        // ==================== SECRETS ====================
        this.claudeApiKeySecret = new secretsmanager.Secret(this, 'ClaudeApiKey', {
            secretName: `${prefix}/claude-api-key`,
            description: `Claude API key for tenant: ${tenantConfig.tenantId}`,
        });

        this.jiraSecret = new secretsmanager.Secret(this, 'JiraCredentials', {
            secretName: `${prefix}/jira-credentials`,
            description: `Jira Cloud API token for tenant: ${tenantConfig.tenantId}`,
        });

        // Source control secrets — only create what the tenant needs
        if (tenantConfig.sourceControlPlatforms.includes('BITBUCKET')) {
            this.bitbucketSecret = new secretsmanager.Secret(this, 'BitbucketCredentials', {
                secretName: `${prefix}/bitbucket-credentials`,
                description: `Bitbucket credentials for tenant: ${tenantConfig.tenantId}`,
            });
        }

        if (tenantConfig.sourceControlPlatforms.includes('GITHUB')) {
            this.githubSecret = new secretsmanager.Secret(this, 'GitHubCredentials', {
                secretName: `${prefix}/github-token`,
                description: `GitHub PAT for tenant: ${tenantConfig.tenantId}`,
            });
        }

        // ==================== DYNAMODB ====================
        this.stateTable = new dynamodb.Table(this, 'StateTable', {
            tableName: `${prefix}-state`,
            partitionKey: { name: 'PK', type: dynamodb.AttributeType.STRING },
            sortKey: { name: 'SK', type: dynamodb.AttributeType.STRING },
            billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
            timeToLiveAttribute: 'ttl',
            removalPolicy: cdk.RemovalPolicy.DESTROY,
            pointInTimeRecoverySpecification: { pointInTimeRecoveryEnabled: true },
        });

        this.stateTable.addGlobalSecondaryIndex({
            indexName: 'GSI1',
            partitionKey: { name: 'GSI1PK', type: dynamodb.AttributeType.STRING },
            sortKey: { name: 'GSI1SK', type: dynamodb.AttributeType.STRING },
            projectionType: dynamodb.ProjectionType.ALL,
        });

        // ==================== S3 ====================
        this.codeContextBucket = new s3.Bucket(this, 'CodeContextBucket', {
            bucketName: `${prefix}-code-context-${cdk.Aws.ACCOUNT_ID}`,
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

        // ==================== SQS (Agent Queue — optional) ====================
        if (tenantConfig.enableAgentMode) {
            this.agentDlq = new sqs.Queue(this, 'AgentTasksDLQ', {
                queueName: `${prefix}-agent-tasks-dlq.fifo`,
                fifo: true,
                retentionPeriod: cdk.Duration.days(14),
            });

            this.agentQueue = new sqs.Queue(this, 'AgentTasksQueue', {
                queueName: `${prefix}-agent-tasks.fifo`,
                fifo: true,
                contentBasedDeduplication: true,
                visibilityTimeout: cdk.Duration.seconds(600),
                deadLetterQueue: {
                    queue: this.agentDlq,
                    maxReceiveCount: 3,
                },
            });
        }
    }
}
