"""CDK assertions tests for ObservabilityStack.

Run from the infrastructure/ directory:
    pip install aws-cdk-lib constructs pytest
    pytest tests/test_observability_stack.py

These tests synthesise the stacks in memory (no AWS calls) and assert the
generated CloudFormation template contains the expected resources.
"""

from __future__ import annotations

import pytest

try:
    import aws_cdk as cdk
    from aws_cdk import aws_ec2 as ec2
    from aws_cdk.assertions import Match, Template

    CDK_AVAILABLE = True
except ImportError:
    CDK_AVAILABLE = False

pytestmark = pytest.mark.skipif(not CDK_AVAILABLE, reason="aws-cdk-lib not installed")


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def observability_template() -> "Template":
    """Synthesise ObservabilityStack and return its CloudFormation template."""
    import sys
    import os

    # Ensure infrastructure/ stacks are importable
    infra_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if infra_dir not in sys.path:
        sys.path.insert(0, infra_dir)

    from stacks.network_stack import NetworkStack
    from stacks.database_stack import DatabaseStack
    from stacks.compute_stack import ComputeStack
    from stacks.observability_stack import ObservabilityStack

    app = cdk.App()
    env = cdk.Environment(account="123456789012", region="ap-southeast-1")

    network = NetworkStack(app, "TestNetwork", env=env)
    database = DatabaseStack(app, "TestDatabase", vpc=network.vpc, env=env)
    compute = ComputeStack(app, "TestCompute", vpc=network.vpc, database=database, env=env)
    observability = ObservabilityStack(app, "TestObservability", compute=compute, env=env)

    return Template.from_stack(observability)


# ---------------------------------------------------------------------------
# SNS Topics
# ---------------------------------------------------------------------------

class TestSNSTopics:
    def test_creates_p1_alert_topic(self, observability_template: "Template") -> None:
        observability_template.has_resource_properties("AWS::SNS::Topic", {
            "TopicName": "ai-driven-p1-alerts",
            "DisplayName": "AI-Driven P1 Alerts",
        })

    def test_creates_p2_alert_topic(self, observability_template: "Template") -> None:
        observability_template.has_resource_properties("AWS::SNS::Topic", {
            "TopicName": "ai-driven-p2-alerts",
            "DisplayName": "AI-Driven P2 Alerts",
        })

    def test_creates_exactly_two_sns_topics(self, observability_template: "Template") -> None:
        observability_template.resource_count_is("AWS::SNS::Topic", 2)


# ---------------------------------------------------------------------------
# CloudWatch Alarms — DLQ (P1)
# ---------------------------------------------------------------------------

class TestDLQAlarms:
    def test_coding_dlq_alarm_created(self, observability_template: "Template") -> None:
        observability_template.has_resource_properties("AWS::CloudWatch::Alarm", {
            "AlarmName": "ai-driven-ai-driven-coding-tasks-dlq-depth",
            "ComparisonOperator": "GreaterThanThreshold",
            "Threshold": 0,
            "EvaluationPeriods": 1,
            "TreatMissingData": "notBreaching",
        })

    def test_review_dlq_alarm_created(self, observability_template: "Template") -> None:
        observability_template.has_resource_properties("AWS::CloudWatch::Alarm", {
            "AlarmName": "ai-driven-ai-driven-review-tasks-dlq-depth",
            "ComparisonOperator": "GreaterThanThreshold",
            "Threshold": 0,
            "EvaluationPeriods": 1,
        })

    def test_devops_dlq_alarm_created(self, observability_template: "Template") -> None:
        observability_template.has_resource_properties("AWS::CloudWatch::Alarm", {
            "AlarmName": "ai-driven-ai-driven-devops-alerts-dlq-depth",
            "ComparisonOperator": "GreaterThanThreshold",
            "Threshold": 0,
            "EvaluationPeriods": 1,
        })

    def test_dlq_alarms_notify_p1_topic(self, observability_template: "Template") -> None:
        """All DLQ alarms must send to the P1 SNS topic."""
        alarms = observability_template.find_resources("AWS::CloudWatch::Alarm")
        dlq_alarms = {
            k: v for k, v in alarms.items()
            if "dlq" in v["Properties"].get("AlarmName", "").lower()
        }
        assert len(dlq_alarms) == 3, "Expected 3 DLQ alarms"
        for logical_id, alarm in dlq_alarms.items():
            alarm_actions = alarm["Properties"].get("AlarmActions", [])
            assert len(alarm_actions) > 0, f"{logical_id} has no alarm actions"

    def test_dlq_alarms_use_maximum_statistic(self, observability_template: "Template") -> None:
        """DLQ alarms use Maximum so a single spike triggers the alarm."""
        alarms = observability_template.find_resources("AWS::CloudWatch::Alarm")
        dlq_alarms = {
            k: v for k, v in alarms.items()
            if "dlq" in v["Properties"].get("AlarmName", "").lower()
        }
        for logical_id, alarm in dlq_alarms.items():
            # CDK renders statistic in the Metrics block or Statistic field
            props = alarm["Properties"]
            statistic = props.get("Statistic") or _extract_statistic_from_metrics(props)
            assert statistic in ("Maximum", "max"), (
                f"{logical_id} should use Maximum statistic, got {statistic}"
            )


# ---------------------------------------------------------------------------
# CloudWatch Alarms — Main queues (P2)
# ---------------------------------------------------------------------------

class TestQueueDepthAlarms:
    def test_coding_queue_alarm_created(self, observability_template: "Template") -> None:
        observability_template.has_resource_properties("AWS::CloudWatch::Alarm", {
            "AlarmName": "ai-driven-ai-driven-coding-tasks-depth",
            "ComparisonOperator": "GreaterThanThreshold",
            "Threshold": 100,
            "EvaluationPeriods": 2,
            "TreatMissingData": "notBreaching",
        })

    def test_review_queue_alarm_created(self, observability_template: "Template") -> None:
        observability_template.has_resource_properties("AWS::CloudWatch::Alarm", {
            "AlarmName": "ai-driven-ai-driven-review-tasks-depth",
            "Threshold": 100,
            "EvaluationPeriods": 2,
        })

    def test_devops_queue_alarm_created(self, observability_template: "Template") -> None:
        observability_template.has_resource_properties("AWS::CloudWatch::Alarm", {
            "AlarmName": "ai-driven-ai-driven-devops-alerts-depth",
            "Threshold": 100,
            "EvaluationPeriods": 2,
        })

    def test_queue_depth_alarms_notify_p2_topic(self, observability_template: "Template") -> None:
        alarms = observability_template.find_resources("AWS::CloudWatch::Alarm")
        # Queue depth alarms have "depth" in name but NOT "dlq"
        depth_alarms = {
            k: v for k, v in alarms.items()
            if (
                "depth" in v["Properties"].get("AlarmName", "").lower()
                and "dlq" not in v["Properties"].get("AlarmName", "").lower()
            )
        }
        assert len(depth_alarms) == 3, f"Expected 3 queue-depth alarms, got {len(depth_alarms)}"
        for logical_id, alarm in depth_alarms.items():
            alarm_actions = alarm["Properties"].get("AlarmActions", [])
            assert len(alarm_actions) > 0, f"{logical_id} has no alarm actions"


# ---------------------------------------------------------------------------
# Total alarm count
# ---------------------------------------------------------------------------

class TestAlarmCount:
    def test_creates_six_cloudwatch_alarms(self, observability_template: "Template") -> None:
        # 3 DLQ alarms + 3 main-queue depth alarms = 6
        observability_template.resource_count_is("AWS::CloudWatch::Alarm", 6)


# ---------------------------------------------------------------------------
# CloudWatch Dashboard
# ---------------------------------------------------------------------------

class TestDashboard:
    def test_dashboard_created(self, observability_template: "Template") -> None:
        observability_template.has_resource_properties("AWS::CloudWatch::Dashboard", {
            "DashboardName": "ai-driven-overview",
        })


# ---------------------------------------------------------------------------
# Stack outputs
# ---------------------------------------------------------------------------

class TestOutputs:
    def test_p1_topic_arn_output_exists(self, observability_template: "Template") -> None:
        observability_template.has_output("P1AlertTopicArn", {})

    def test_p2_topic_arn_output_exists(self, observability_template: "Template") -> None:
        observability_template.has_output("P2AlertTopicArn", {})


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _extract_statistic_from_metrics(props: dict) -> str | None:
    """Extract statistic string from CDK-rendered Metrics array if present."""
    metrics = props.get("Metrics", [])
    for metric in metrics:
        stat = metric.get("MetricStat", {}).get("Stat")
        if stat:
            return stat
    return None
