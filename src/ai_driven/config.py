"""Application configuration loaded from environment variables."""

from enum import Enum
from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings


class ClaudeProviderType(str, Enum):
    ANTHROPIC = "anthropic"
    BEDROCK = "bedrock"


class Environment(str, Enum):
    LOCAL = "local"
    DEV = "dev"
    STAGING = "staging"
    PRODUCTION = "production"


class Settings(BaseSettings):
    model_config = {"env_prefix": "AI_DRIVEN_", "env_file": (".env", ".env.local"), "extra": "ignore"}

    # App
    app_name: str = "ai-driven"
    environment: Environment = Environment.LOCAL
    debug: bool = False
    log_level: str = "INFO"
    task_timeout_seconds: int = 1200  # 20 minutes hard cap per task

    # API
    api_host: str = "0.0.0.0"
    api_port: int = 8000

    # Claude
    claude_provider: ClaudeProviderType = ClaudeProviderType.ANTHROPIC
    claude_default_model: str = "claude-sonnet-4-6"
    claude_planning_model: str = "claude-opus-4-7"  # bumped 2026-04-22 — see docs/implementation/09 §11
    claude_fast_model: str = "claude-haiku-4-5"
    # Output token cap. 4096 was too tight for coding runs — Sonnet 4.6's
    # final "open the PR, summarize, update Jira" turn routinely writes
    # >4k tokens (full PR body + tool calls) and got truncated mid-response,
    # which prevented github.create_pr from firing (see KAN-10, 2026-04-25).
    # Bump to 16384; models support this cleanly and the wall-clock cost
    # is bounded by the request timeout, not the token cap.
    claude_max_tokens: int = 16384
    # Coding runs that touch ≥10 files often need 2 iterations per file
    # (read → write) plus explore + plan + deliver. 20 was under-budgeted —
    # KAN-9 (fe4e7f5c) burned 20 iterations writing files and never reached
    # the PR step. 50 gives enough headroom for large tickets without
    # encouraging runaway loops; raise further only after a new regression.
    claude_max_tool_iterations: int = 50
    anthropic_api_key: str = ""

    # AWS
    aws_region: str = "eu-central-1"
    aws_account_id: str = ""
    # Let the backend use explicit creds from .env.local so boto3 doesn't
    # silently fall through to ~/.aws/credentials (which on this machine has
    # a sentinel 'test' access key for LocalStack). Empty string means "let
    # boto3 resolve from the default chain" — same as before.
    #
    # These read the *bare* AWS_* env-var names rather than AI_DRIVEN_AWS_*
    # so users don't have to duplicate the standard boto3 names in .env.local.
    aws_access_key_id: str = Field(default="", validation_alias="AWS_ACCESS_KEY_ID")
    aws_secret_access_key: str = Field(default="", validation_alias="AWS_SECRET_ACCESS_KEY")
    aws_session_token: str = Field(default="", validation_alias="AWS_SESSION_TOKEN")

    # Sandbox
    sandbox_mode: str = "mock"  # "mock" for local dev, "docker" for production

    # RAG
    rag_enabled: bool = False
    rag_auto_enable: bool = True
    rag_auto_enable_file_threshold: int = 500
    rag_chunk_size: int = 1000
    voyage_api_key: str = ""

    # Evaluation
    # Default ON — in debug mode the Haiku grader is satisfied by MockProvider,
    # in prod it's gated by the presence of a real Claude provider anyway.
    evaluation_enabled: bool = True

    # Bedrock — use cross-region inference profile IDs (`global.` prefix).
    # Plain regional IDs (`anthropic.claude-sonnet-4-6`) only work on-demand
    # in us-east-1/us-west-2; elsewhere (ap-southeast-1, eu-central-1, …)
    # Anthropic 4.x models require an inference profile. `global.*` works in
    # every region that has Bedrock Anthropic access.
    # Override per-env via AI_DRIVEN_BEDROCK_MODEL_ID / AI_DRIVEN_BEDROCK_PLANNING_MODEL_ID.
    bedrock_model_id: str = "global.anthropic.claude-sonnet-4-6"
    bedrock_planning_model_id: str = "global.anthropic.claude-opus-4-7"
    bedrock_fast_model_id: str = "global.anthropic.claude-haiku-4-5-20251001-v1:0"

    # PostgreSQL
    postgres_host: str = "localhost"
    postgres_port: int = 5432
    postgres_user: str = "ai_driven"
    postgres_password: str = ""
    postgres_db: str = "ai_driven"

    @property
    def postgres_url(self) -> str:
        return (
            f"postgresql+asyncpg://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
        )

    # DynamoDB
    dynamodb_table_prefix: str = "ai-driven"
    dynamodb_endpoint_url: str | None = None

    # Task execution
    task_timeout_seconds: int = 1200  # 20-minute hard cap for inline task processing

    # SQS
    sqs_task_queue_url: str = ""
    sqs_review_queue_url: str = ""
    sqs_devops_queue_url: str = ""

    # Redis
    redis_url: str = "redis://localhost:6379/0"

    # Session-backed login (Sprint 14, docs/implementation/14-login-ux-overhaul.md).
    # When true, the frontend must exchange an API key for a short-lived session
    # token via POST /api/auth/login; the X-API-Key path stays open for agents
    # and CI. Default off so rollout is reversible.
    ui_auth_require_session: bool = False
    session_ttl_seconds: int = 24 * 3600  # 24h sliding
    session_max_age_seconds: int = 30 * 24 * 3600  # 30d hard cap

    # GitHub
    github_app_id: str = ""
    github_app_private_key: str = ""
    github_webhook_secret: str = ""

    # Bitbucket
    bitbucket_client_id: str = ""
    bitbucket_client_secret: str = ""
    bitbucket_webhook_secret: str = ""

    # Jira
    jira_base_url: str = ""
    jira_email: str = ""
    jira_api_token: str = ""
    jira_acceptance_criteria_field: str = "customfield_10028"

    # Slack
    slack_bot_token: str = ""
    slack_signing_secret: str = ""
    slack_app_token: str = ""
    slack_default_channel: str = ""

    # MCP
    mcp_enabled: bool = False
    mcp_github_enabled: bool = False
    # Context7 needs no credentials; safe to enable by default so the
    # "MCP integration" feature shows ≥1 active server out of the box.
    mcp_context7_enabled: bool = True
    mcp_jira_enabled: bool = False
    mcp_slack_enabled: bool = False
    mcp_datadog_enabled: bool = False

    # Phase A of docs/implementation/12-scm-via-mcp.md: route the SCMClient
    # protocol through the official github/github-mcp-server instead of the
    # in-process GitHubClient. Default off — flip to true after staging burn-in.
    scm_github_via_mcp: bool = False
    # Pin the binary / npm package used for the official GitHub MCP server.
    # Defaults to the npx-installable package so zero setup is needed locally.
    github_mcp_command: str = "npx"
    github_mcp_args: str = "-y,@github/github-mcp-server"

    # SCM tokens (direct token auth, alternative to App-based auth)
    github_token: str = ""
    bitbucket_username: str = ""
    bitbucket_app_password: str = ""

    # DataDog
    dd_service: str = "ai-driven"
    dd_env: str = "local"
    dd_api_key: str = ""
    dd_app_key: str = ""
    datadog_enabled: bool = False


@lru_cache
def get_settings() -> Settings:
    return Settings()
