"""Database stack — Aurora PostgreSQL Serverless v2 + DynamoDB."""

import aws_cdk as cdk
from aws_cdk import (
    aws_dynamodb as dynamodb,
    aws_ec2 as ec2,
    aws_rds as rds,
    aws_secretsmanager as sm,
)
from constructs import Construct


class DatabaseStack(cdk.Stack):
    def __init__(self, scope: Construct, id: str, vpc: ec2.Vpc, **kwargs) -> None:
        super().__init__(scope, id, **kwargs)

        # Aurora PostgreSQL Serverless v2
        self.db_secret = sm.Secret(
            self, "DbSecret",
            generate_secret_string=sm.SecretStringGenerator(
                secret_string_template='{"username": "ai_driven"}',
                generate_string_key="password",
                exclude_punctuation=True,
            ),
        )

        self.db_security_group = ec2.SecurityGroup(
            self, "DbSg", vpc=vpc, description="Aurora PostgreSQL SG",
        )

        self.aurora_cluster = rds.DatabaseCluster(
            self, "AuroraCluster",
            engine=rds.DatabaseClusterEngine.aurora_postgres(version=rds.AuroraPostgresEngineVersion.VER_16_4),
            vpc=vpc,
            vpc_subnets=ec2.SubnetSelection(subnet_type=ec2.SubnetType.PRIVATE_ISOLATED),
            security_groups=[self.db_security_group],
            credentials=rds.Credentials.from_secret(self.db_secret),
            default_database_name="ai_driven",
            serverless_v2_min_capacity=0.5,
            serverless_v2_max_capacity=8,
            writer=rds.ClusterInstance.serverless_v2("Writer"),
            readers=[rds.ClusterInstance.serverless_v2("Reader", scale_with_writer=True)],
            removal_policy=cdk.RemovalPolicy.SNAPSHOT,
        )

        # DynamoDB tables
        self.agent_sessions_table = dynamodb.Table(
            self, "AgentSessions",
            table_name="ai-driven-agent-sessions",
            partition_key=dynamodb.Attribute(name="session_id", type=dynamodb.AttributeType.STRING),
            sort_key=dynamodb.Attribute(name="timestamp", type=dynamodb.AttributeType.STRING),
            billing_mode=dynamodb.BillingMode.PAY_PER_REQUEST,
            removal_policy=cdk.RemovalPolicy.DESTROY,
        )

        self.webhook_events_table = dynamodb.Table(
            self, "WebhookEvents",
            table_name="ai-driven-webhook-events",
            partition_key=dynamodb.Attribute(name="event_id", type=dynamodb.AttributeType.STRING),
            sort_key=dynamodb.Attribute(name="source_timestamp", type=dynamodb.AttributeType.STRING),
            billing_mode=dynamodb.BillingMode.PAY_PER_REQUEST,
            removal_policy=cdk.RemovalPolicy.DESTROY,
            time_to_live_attribute="ttl",
        )

        self.task_state_table = dynamodb.Table(
            self, "TaskStateMachine",
            table_name="ai-driven-task-state-machine",
            partition_key=dynamodb.Attribute(name="task_id", type=dynamodb.AttributeType.STRING),
            sort_key=dynamodb.Attribute(name="state_timestamp", type=dynamodb.AttributeType.STRING),
            billing_mode=dynamodb.BillingMode.PAY_PER_REQUEST,
            removal_policy=cdk.RemovalPolicy.DESTROY,
        )

        cdk.CfnOutput(self, "AuroraEndpoint", value=self.aurora_cluster.cluster_endpoint.hostname)
