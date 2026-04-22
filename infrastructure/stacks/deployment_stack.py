"""Blue/green deployment stack — ALB + ECS + CodeDeploy."""

import aws_cdk as cdk
from aws_cdk import (
    aws_cloudwatch as cw,
    aws_codedeploy as codedeploy,
    aws_ec2 as ec2,
    aws_ecs as ecs,
    aws_elasticloadbalancingv2 as elbv2,
    aws_iam as iam,
)
from constructs import Construct


class DeploymentStack(cdk.Stack):
    """Blue/green deployment infrastructure using ALB + ECS + CodeDeploy.

    Traffic shifting strategy: all-at-once (simplest, instant rollback).
    Database migrations must be run separately before deploying — they must be
    backward-compatible so both blue and green task sets can run concurrently
    during the brief cutover window.
    """

    def __init__(
        self,
        scope: Construct,
        id: str,
        *,
        vpc: ec2.Vpc,
        cluster: ecs.Cluster,
        api_service: ecs.FargateService,
        **kwargs,
    ) -> None:
        super().__init__(scope, id, **kwargs)

        stage = self.node.try_get_context("stage") or "dev"

        # ------------------------------------------------------------------ #
        # Security group — ALB
        # ------------------------------------------------------------------ #
        alb_sg = ec2.SecurityGroup(
            self,
            "AlbSg",
            vpc=vpc,
            description="ALB security group — blue/green",
            allow_all_outbound=True,
        )
        alb_sg.add_ingress_rule(ec2.Peer.any_ipv4(), ec2.Port.tcp(80), "HTTP from internet")
        alb_sg.add_ingress_rule(ec2.Peer.any_ipv4(), ec2.Port.tcp(443), "HTTPS from internet")

        # ------------------------------------------------------------------ #
        # ALB
        # ------------------------------------------------------------------ #
        self.alb = elbv2.ApplicationLoadBalancer(
            self,
            "Alb",
            load_balancer_name=f"{stage}-api-alb",
            vpc=vpc,
            internet_facing=True,
            security_group=alb_sg,
            vpc_subnets=ec2.SubnetSelection(subnet_type=ec2.SubnetType.PUBLIC),
        )

        # ------------------------------------------------------------------ #
        # Target groups — blue (live) and green (replacement)
        # ------------------------------------------------------------------ #
        self.blue_tg = elbv2.ApplicationTargetGroup(
            self,
            "BlueTg",
            target_group_name=f"{stage}-api-blue",
            vpc=vpc,
            port=8000,
            protocol=elbv2.ApplicationProtocol.HTTP,
            target_type=elbv2.TargetType.IP,
            health_check=elbv2.HealthCheck(
                path="/health",
                interval=cdk.Duration.seconds(30),
                timeout=cdk.Duration.seconds(10),
                healthy_threshold_count=2,
                unhealthy_threshold_count=3,
                healthy_http_codes="200",
            ),
            deregistration_delay=cdk.Duration.seconds(30),
        )

        self.green_tg = elbv2.ApplicationTargetGroup(
            self,
            "GreenTg",
            target_group_name=f"{stage}-api-green",
            vpc=vpc,
            port=8000,
            protocol=elbv2.ApplicationProtocol.HTTP,
            target_type=elbv2.TargetType.IP,
            health_check=elbv2.HealthCheck(
                path="/health",
                interval=cdk.Duration.seconds(30),
                timeout=cdk.Duration.seconds(10),
                healthy_threshold_count=2,
                unhealthy_threshold_count=3,
                healthy_http_codes="200",
            ),
            deregistration_delay=cdk.Duration.seconds(30),
        )

        # ------------------------------------------------------------------ #
        # Listeners
        # ------------------------------------------------------------------ #
        # Production listener — port 80, forwards to blue by default
        self.prod_listener = self.alb.add_listener(
            "ProdListener",
            port=80,
            protocol=elbv2.ApplicationProtocol.HTTP,
            default_target_groups=[self.blue_tg],
        )

        # Test listener — port 8080, forwards to green (pre-prod validation)
        self.test_listener = self.alb.add_listener(
            "TestListener",
            port=8080,
            protocol=elbv2.ApplicationProtocol.HTTP,
            default_target_groups=[self.green_tg],
        )

        # Allow test port from ALB sg
        alb_sg.add_ingress_rule(ec2.Peer.any_ipv4(), ec2.Port.tcp(8080), "Test listener port")

        # ------------------------------------------------------------------ #
        # Attach blue TG to the existing ECS service
        # CodeDeploy takes over routing after the first deployment
        # ------------------------------------------------------------------ #
        api_service.attach_to_application_target_group(self.blue_tg)

        # ------------------------------------------------------------------ #
        # CloudWatch alarm — 5xx errors trigger automatic rollback
        # ------------------------------------------------------------------ #
        error_alarm = cw.Alarm(
            self,
            "ApiErrorAlarm",
            alarm_name=f"{stage}-api-5xx-errors",
            alarm_description="ALB 5xx error rate — triggers CodeDeploy rollback",
            metric=self.alb.metric_http_code_elb(
                elbv2.HttpCodeElb.ELB_5XX_COUNT,
                period=cdk.Duration.minutes(1),
                statistic="Sum",
            ),
            threshold=10,
            evaluation_periods=2,
            comparison_operator=cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treat_missing_data=cw.TreatMissingData.NOT_BREACHING,
        )

        # ------------------------------------------------------------------ #
        # CodeDeploy application
        # ------------------------------------------------------------------ #
        self.codedeploy_app = codedeploy.EcsApplication(
            self,
            "CodeDeployApp",
            application_name=f"{stage}-api",
        )

        # IAM role for CodeDeploy
        codedeploy_role = iam.Role(
            self,
            "CodeDeployRole",
            assumed_by=iam.ServicePrincipal("codedeploy.amazonaws.com"),
            managed_policies=[
                iam.ManagedPolicy.from_aws_managed_policy_name("AWSCodeDeployRoleForECS"),
            ],
        )

        # ------------------------------------------------------------------ #
        # CodeDeploy deployment group — blue/green, all-at-once traffic shift
        # ------------------------------------------------------------------ #
        self.deployment_group = codedeploy.EcsDeploymentGroup(
            self,
            "DeploymentGroup",
            application=self.codedeploy_app,
            deployment_group_name=f"{stage}-api-bg",
            service=api_service,
            blue_green_deployment_config=codedeploy.EcsBlueGreenDeploymentConfig(
                listener=self.prod_listener,
                test_listener=self.test_listener,
                blue_target_group=self.blue_tg,
                green_target_group=self.green_tg,
                # After successful deployment, terminate original (blue) task set after 5 min
                terminate_blue_instances_on_deployment_success=codedeploy.InstanceTerminationWaitTime.after(
                    cdk.Duration.minutes(5)
                ),
                # Allow up to 60 minutes to complete deployment before auto-rollback
                deployment_approval_wait_time=cdk.Duration.minutes(60),
            ),
            deployment_config=codedeploy.EcsDeploymentConfig.ALL_AT_ONCE,
            auto_rollback=codedeploy.AutoRollbackConfig(
                failed_deployment=True,
                deployment_in_alarm=True,
                stopped_deployment=False,
            ),
            alarms=[error_alarm],
            role=codedeploy_role,
        )

        # ------------------------------------------------------------------ #
        # Outputs
        # ------------------------------------------------------------------ #
        cdk.CfnOutput(
            self,
            "AlbDnsName",
            value=self.alb.load_balancer_dns_name,
            description="ALB DNS name for API traffic",
        )
        cdk.CfnOutput(
            self,
            "CodeDeployAppName",
            value=self.codedeploy_app.application_name,
            description="CodeDeploy application name",
        )
        cdk.CfnOutput(
            self,
            "DeploymentGroupName",
            value=self.deployment_group.deployment_group_name,
            description="CodeDeploy deployment group name",
        )
        cdk.CfnOutput(
            self,
            "BlueTgArn",
            value=self.blue_tg.target_group_arn,
            description="Blue target group ARN",
        )
        cdk.CfnOutput(
            self,
            "GreenTgArn",
            value=self.green_tg.target_group_arn,
            description="Green target group ARN",
        )
