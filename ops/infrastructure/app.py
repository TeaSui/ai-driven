#!/usr/bin/env python3
"""AWS CDK app entrypoint for AI-Driven Development Platform."""

import aws_cdk as cdk

from stacks.network_stack import NetworkStack
from stacks.database_stack import DatabaseStack
from stacks.compute_stack import ComputeStack
from stacks.api_stack import ApiStack
from stacks.deployment_stack import DeploymentStack
from stacks.observability_stack import ObservabilityStack

app = cdk.App()

env = cdk.Environment(
    account=app.node.try_get_context("account"),
    region=app.node.try_get_context("region") or "eu-central-1",
)

stage = app.node.try_get_context("stage") or "dev"

network = NetworkStack(app, f"ai-driven-{stage}-network", env=env)
database = DatabaseStack(app, f"ai-driven-{stage}-database", vpc=network.vpc, env=env)
compute = ComputeStack(
    app, f"ai-driven-{stage}-compute",
    vpc=network.vpc,
    database=database,
    env=env,
)
api = ApiStack(app, f"ai-driven-{stage}-api", compute=compute, env=env)
deployment = DeploymentStack(
    app, f"ai-driven-{stage}-deployment",
    vpc=network.vpc,
    cluster=compute.cluster,
    api_service=compute.api_service,
    env=env,
)
observability = ObservabilityStack(
    app, f"ai-driven-{stage}-observability",
    compute=compute,
    env=env,
)

app.synth()
