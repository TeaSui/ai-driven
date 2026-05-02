"""Observability stack — DataDog integration, CloudWatch alarms."""

import aws_cdk as cdk
from aws_cdk import aws_cloudwatch as cw, aws_cloudwatch_actions as cwa, aws_sns as sns
from constructs import Construct

from stacks.compute_stack import ComputeStack


class ObservabilityStack(cdk.Stack):
    def __init__(
        self,
        scope: Construct,
        id: str,
        compute: ComputeStack,
        **kwargs,
    ) -> None:
        super().__init__(scope, id, **kwargs)

        # SNS topic for P1 alerts (wire to PagerDuty / email / Slack externally)
        self.p1_topic = sns.Topic(
            self, "P1AlertTopic",
            topic_name="ai-driven-p1-alerts",
            display_name="AI-Driven P1 Alerts",
        )

        # SNS topic for P2 alerts
        self.p2_topic = sns.Topic(
            self, "P2AlertTopic",
            topic_name="ai-driven-p2-alerts",
            display_name="AI-Driven P2 Alerts",
        )

        # --- DLQ depth alarms (P1 — any message in DLQ means a task permanently failed) ---
        dlq_configs = [
            ("CodingDLQ", compute.coding_dlq, "ai-driven-coding-tasks-dlq"),
            ("ReviewDLQ", compute.review_dlq, "ai-driven-review-tasks-dlq"),
            ("DevOpsDLQ", compute.devops_dlq, "ai-driven-devops-alerts-dlq"),
        ]

        for logical_id, dlq_queue, queue_label in dlq_configs:
            alarm = cw.Alarm(
                self,
                f"{logical_id}DepthAlarm",
                alarm_name=f"ai-driven-{queue_label}-depth",
                alarm_description=(
                    f"P1: Messages in {queue_label} — task permanently failed after 3 retries. "
                    "Investigate worker logs immediately."
                ),
                metric=dlq_queue.metric_approximate_number_of_messages_visible(
                    period=cdk.Duration.minutes(5),
                    statistic="Maximum",
                ),
                threshold=0,
                evaluation_periods=1,
                comparison_operator=cw.ComparisonOperator.GREATER_THAN_THRESHOLD,
                treat_missing_data=cw.TreatMissingData.NOT_BREACHING,
            )
            alarm.add_alarm_action(cwa.SnsAction(self.p1_topic))
            alarm.add_ok_action(cwa.SnsAction(self.p1_topic))

        # --- Main queue depth alarms (P2 — backlog growing, workers may be overwhelmed) ---
        queue_configs = [
            ("CodingQueue", compute.coding_queue, "ai-driven-coding-tasks"),
            ("ReviewQueue", compute.review_queue, "ai-driven-review-tasks"),
            ("DevOpsQueue", compute.devops_queue, "ai-driven-devops-alerts"),
        ]

        for logical_id, queue, queue_label in queue_configs:
            alarm = cw.Alarm(
                self,
                f"{logical_id}DepthAlarm",
                alarm_name=f"ai-driven-{queue_label}-depth",
                alarm_description=(
                    f"P2: {queue_label} depth > 100. Workers may be overwhelmed or stalled."
                ),
                metric=queue.metric_approximate_number_of_messages_visible(
                    period=cdk.Duration.minutes(5),
                    statistic="Maximum",
                ),
                threshold=100,
                evaluation_periods=2,
                comparison_operator=cw.ComparisonOperator.GREATER_THAN_THRESHOLD,
                treat_missing_data=cw.TreatMissingData.NOT_BREACHING,
            )
            alarm.add_alarm_action(cwa.SnsAction(self.p2_topic))
            alarm.add_ok_action(cwa.SnsAction(self.p2_topic))

        # CloudWatch dashboard for AI-Driven metrics
        self.dashboard = cw.Dashboard(
            self, "Dashboard",
            dashboard_name="ai-driven-overview",
        )

        # Dashboard widgets — DLQ depths
        self.dashboard.add_widgets(
            cw.GraphWidget(
                title="DLQ Depths (should be 0)",
                left=[
                    compute.coding_dlq.metric_approximate_number_of_messages_visible(
                        period=cdk.Duration.minutes(5), label="Coding DLQ"
                    ),
                    compute.review_dlq.metric_approximate_number_of_messages_visible(
                        period=cdk.Duration.minutes(5), label="Review DLQ"
                    ),
                    compute.devops_dlq.metric_approximate_number_of_messages_visible(
                        period=cdk.Duration.minutes(5), label="DevOps DLQ"
                    ),
                ],
                width=12,
            ),
            cw.GraphWidget(
                title="Queue Depths",
                left=[
                    compute.coding_queue.metric_approximate_number_of_messages_visible(
                        period=cdk.Duration.minutes(5), label="Coding Queue"
                    ),
                    compute.review_queue.metric_approximate_number_of_messages_visible(
                        period=cdk.Duration.minutes(5), label="Review Queue"
                    ),
                    compute.devops_queue.metric_approximate_number_of_messages_visible(
                        period=cdk.Duration.minutes(5), label="DevOps Queue"
                    ),
                ],
                width=12,
            ),
        )

        cdk.CfnOutput(self, "P1AlertTopicArn", value=self.p1_topic.topic_arn)
        cdk.CfnOutput(self, "P2AlertTopicArn", value=self.p2_topic.topic_arn)
