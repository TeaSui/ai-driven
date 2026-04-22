"""API Gateway stack — HTTP API + WebSocket API."""

import aws_cdk as cdk
from aws_cdk import aws_apigatewayv2 as apigw
from constructs import Construct


class ApiStack(cdk.Stack):
    def __init__(self, scope: Construct, id: str, compute: any, **kwargs) -> None:
        super().__init__(scope, id, **kwargs)

        # HTTP API for REST endpoints
        self.http_api = apigw.CfnApi(
            self, "HttpApi",
            name="ai-driven-api",
            protocol_type="HTTP",
            cors_configuration=apigw.CfnApi.CorsProperty(
                allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
                allow_origins=["*"],
                allow_headers=["*"],
            ),
        )

        cdk.CfnOutput(self, "ApiUrl", value=f"https://{self.http_api.ref}.execute-api.{self.region}.amazonaws.com")
