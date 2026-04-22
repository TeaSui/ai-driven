"""Compute stack — ECS Fargate + SQS queues."""

import aws_cdk as cdk
from aws_cdk import (
    aws_ec2 as ec2,
    aws_ecs as ecs,
    aws_iam as iam,
    aws_logs as logs,
    aws_sqs as sqs,
)
from constructs import Construct


class ComputeStack(cdk.Stack):
    def __init__(self, scope: Construct, id: str, vpc: ec2.Vpc, database: any, **kwargs) -> None:
        super().__init__(scope, id, **kwargs)

        # SQS Queues with DLQs
        self.coding_dlq = sqs.Queue(self, "CodingDLQ", retention_period=cdk.Duration.days(14))
        self.coding_queue = sqs.Queue(
            self, "CodingQueue",
            queue_name="ai-driven-coding-tasks",
            visibility_timeout=cdk.Duration.seconds(900),
            dead_letter_queue=sqs.DeadLetterQueue(max_receive_count=3, queue=self.coding_dlq),
        )

        self.review_dlq = sqs.Queue(self, "ReviewDLQ", retention_period=cdk.Duration.days(14))
        self.review_queue = sqs.Queue(
            self, "ReviewQueue",
            queue_name="ai-driven-review-tasks",
            visibility_timeout=cdk.Duration.seconds(600),
            dead_letter_queue=sqs.DeadLetterQueue(max_receive_count=3, queue=self.review_dlq),
        )

        self.devops_dlq = sqs.Queue(self, "DevOpsDLQ", retention_period=cdk.Duration.days(14))
        self.devops_queue = sqs.Queue(
            self, "DevOpsQueue",
            queue_name="ai-driven-devops-alerts",
            visibility_timeout=cdk.Duration.seconds(300),
            dead_letter_queue=sqs.DeadLetterQueue(max_receive_count=3, queue=self.devops_dlq),
        )

        # ECS Cluster
        self.cluster = ecs.Cluster(
            self, "Cluster",
            vpc=vpc,
            container_insights_v2=ecs.ContainerInsights.ENABLED,
        )

        # Worker Task Definition
        worker_task_def = ecs.FargateTaskDefinition(
            self, "WorkerTaskDef",
            cpu=2048,
            memory_limit_mib=4096,
        )

        worker_task_def.add_container(
            "Worker",
            image=ecs.ContainerImage.from_asset(".", file="docker/Dockerfile.worker"),
            logging=ecs.LogDrivers.aws_logs(
                stream_prefix="worker",
                log_retention=logs.RetentionDays.ONE_MONTH,
            ),
            environment={
                "AI_DRIVEN_ENVIRONMENT": "production",
            },
        )

        # Grant permissions
        self.coding_queue.grant_consume_messages(worker_task_def.task_role)
        self.review_queue.grant_consume_messages(worker_task_def.task_role)
        self.devops_queue.grant_consume_messages(worker_task_def.task_role)
        database.agent_sessions_table.grant_read_write_data(worker_task_def.task_role)
        database.task_state_table.grant_read_write_data(worker_task_def.task_role)

        # Bedrock access
        worker_task_def.task_role.add_to_policy(
            iam.PolicyStatement(
                actions=["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"],
                resources=["*"],
            )
        )

        # API Task Definition
        api_task_def = ecs.FargateTaskDefinition(
            self, "ApiTaskDef",
            cpu=1024,
            memory_limit_mib=2048,
        )

        api_container = api_task_def.add_container(
            "Api",
            image=ecs.ContainerImage.from_asset(".", file="docker/Dockerfile.api"),
            logging=ecs.LogDrivers.aws_logs(
                stream_prefix="api",
                log_retention=logs.RetentionDays.ONE_MONTH,
            ),
            port_mappings=[ecs.PortMapping(container_port=8000)],
        )

        # API Fargate Service
        self.api_service = ecs.FargateService(
            self, "ApiService",
            cluster=self.cluster,
            task_definition=api_task_def,
            desired_count=2,
            assign_public_ip=False,
            vpc_subnets=ec2.SubnetSelection(subnet_type=ec2.SubnetType.PRIVATE_WITH_EGRESS),
        )

        # Grant SQS send to API
        self.coding_queue.grant_send_messages(api_task_def.task_role)
        self.review_queue.grant_send_messages(api_task_def.task_role)
        self.devops_queue.grant_send_messages(api_task_def.task_role)

        cdk.CfnOutput(self, "CodingQueueUrl", value=self.coding_queue.queue_url)
        cdk.CfnOutput(self, "ReviewQueueUrl", value=self.review_queue.queue_url)
        cdk.CfnOutput(self, "DevOpsQueueUrl", value=self.devops_queue.queue_url)
