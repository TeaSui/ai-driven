"""CDK deployment pipeline — source → build → test → deploy."""

import aws_cdk as cdk
from aws_cdk import (
    aws_codebuild as codebuild,
    aws_codepipeline as codepipeline,
    aws_codepipeline_actions as actions,
    aws_ecr as ecr,
    aws_iam as iam,
)
from constructs import Construct


class PipelineStack(cdk.Stack):
    """CI/CD pipeline: GitHub source → Docker build → test → ECS deploy."""

    def __init__(
        self,
        scope: Construct,
        id: str,
        *,
        github_owner: str = "org",
        github_repo: str = "ai-driven",
        github_branch: str = "main",
        **kwargs,
    ) -> None:
        super().__init__(scope, id, **kwargs)

        # ECR repositories
        api_repo = ecr.Repository(
            self, "ApiEcrRepo",
            repository_name="ai-driven-api",
            removal_policy=cdk.RemovalPolicy.RETAIN,
        )
        worker_repo = ecr.Repository(
            self, "WorkerEcrRepo",
            repository_name="ai-driven-worker",
            removal_policy=cdk.RemovalPolicy.RETAIN,
        )

        # Source artifact
        source_output = codepipeline.Artifact("SourceOutput")
        build_output = codepipeline.Artifact("BuildOutput")

        # GitHub source action (requires GitHub connection in AWS)
        source_action = actions.CodeStarConnectionsSourceAction(
            action_name="GitHub_Source",
            owner=github_owner,
            repo=github_repo,
            branch=github_branch,
            output=source_output,
            connection_arn=cdk.CfnParameter(
                self, "GitHubConnectionArn",
                type="String",
                description="ARN of the GitHub CodeStar connection",
            ).value_as_string,
        )

        # Build project — build Docker images and run tests
        build_project = codebuild.PipelineProject(
            self, "BuildProject",
            environment=codebuild.BuildEnvironment(
                build_image=codebuild.LinuxBuildImage.STANDARD_7_0,
                privileged=True,  # Required for Docker builds
            ),
            environment_variables={
                "API_ECR_REPO": codebuild.BuildEnvironmentVariable(
                    value=api_repo.repository_uri,
                ),
                "WORKER_ECR_REPO": codebuild.BuildEnvironmentVariable(
                    value=worker_repo.repository_uri,
                ),
            },
            build_spec=codebuild.BuildSpec.from_object({
                "version": "0.2",
                "phases": {
                    "install": {
                        "commands": [
                            "pip install -e '.[dev]'",
                        ],
                    },
                    "pre_build": {
                        "commands": [
                            "pytest tests/ -v --tb=short",
                            "aws ecr get-login-password --region $AWS_DEFAULT_REGION | docker login --username AWS --password-stdin $API_ECR_REPO",
                        ],
                    },
                    "build": {
                        "commands": [
                            "docker build -f docker/Dockerfile.api -t $API_ECR_REPO:$CODEBUILD_RESOLVED_SOURCE_VERSION .",
                            "docker build -f docker/Dockerfile.worker -t $WORKER_ECR_REPO:$CODEBUILD_RESOLVED_SOURCE_VERSION .",
                            "docker push $API_ECR_REPO:$CODEBUILD_RESOLVED_SOURCE_VERSION",
                            "docker push $WORKER_ECR_REPO:$CODEBUILD_RESOLVED_SOURCE_VERSION",
                            "docker tag $API_ECR_REPO:$CODEBUILD_RESOLVED_SOURCE_VERSION $API_ECR_REPO:latest",
                            "docker tag $WORKER_ECR_REPO:$CODEBUILD_RESOLVED_SOURCE_VERSION $WORKER_ECR_REPO:latest",
                            "docker push $API_ECR_REPO:latest",
                            "docker push $WORKER_ECR_REPO:latest",
                        ],
                    },
                },
                "artifacts": {
                    "files": ["**/*"],
                },
            }),
        )

        # Grant ECR push permissions
        api_repo.grant_pull_push(build_project)
        worker_repo.grant_pull_push(build_project)

        # Manual approval before production deploy
        approve_action = actions.ManualApprovalAction(
            action_name="ApproveDeployment",
            additional_information="Review build artifacts before deploying to production.",
        )

        # Pipeline
        codepipeline.Pipeline(
            self, "DeploymentPipeline",
            pipeline_name="ai-driven-pipeline",
            stages=[
                codepipeline.StageProps(
                    stage_name="Source",
                    actions=[source_action],
                ),
                codepipeline.StageProps(
                    stage_name="Build_And_Test",
                    actions=[
                        actions.CodeBuildAction(
                            action_name="Build",
                            project=build_project,
                            input=source_output,
                            outputs=[build_output],
                        ),
                    ],
                ),
                codepipeline.StageProps(
                    stage_name="Approve",
                    actions=[approve_action],
                ),
            ],
        )
