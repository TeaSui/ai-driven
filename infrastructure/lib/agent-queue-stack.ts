
import * as cdk from 'aws-cdk-lib';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { Construct } from 'constructs';

export class AgentQueueStack extends cdk.Stack {
    public readonly agentQueue: sqs.Queue;
    public readonly dlq: sqs.Queue;

    constructor(scope: Construct, id: string, props?: cdk.StackProps) {
        super(scope, id, props);

        // Dead Letter Queue
        this.dlq = new sqs.Queue(this, 'AgentTasksDLQ', {
            queueName: 'ai-driven-agent-tasks-dlq.fifo',
            fifo: true,
            retentionPeriod: cdk.Duration.days(14),
        });

        // Main Agent Tasks Queue
        this.agentQueue = new sqs.Queue(this, 'AgentTasksQueue', {
            queueName: 'ai-driven-agent-tasks.fifo',
            fifo: true,
            contentBasedDeduplication: true,
            visibilityTimeout: cdk.Duration.seconds(600), // 10 minutes to match Lambda timeout
            deadLetterQueue: {
                queue: this.dlq,
                maxReceiveCount: 3,
            },
        });

        new cdk.CfnOutput(this, 'AgentQueueUrl', {
            value: this.agentQueue.queueUrl,
            description: 'URL of the Agent Tasks FIFO Queue',
        });
    }
}
