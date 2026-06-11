"""Application configuration loaded from environment variables."""

from enum import Enum
from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr
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
    log_level: str = "INFO"
    # Echo every SQL statement to stdout. Local-dev convenience; off by
    # default. AI_DRIVEN_SQL_ECHO=true turns it on independently of
    # environment or log level.
    sql_echo: bool = False
    task_timeout_seconds: int = 1200  # 20 minutes hard cap per task

    # API
    api_host: str = "0.0.0.0"
    api_port: int = 8000

    # Claude
    claude_provider: ClaudeProviderType = ClaudeProviderType.ANTHROPIC
    # All three slots pinned to Sonnet 4.6 (2026-04-30). Rationale:
    # KAN-12 cost post-mortem showed Opus planning iterations alone could
    # eclipse the Sonnet per-task budget at $15/M input, and Haiku's
    # per-token savings get swamped by quality-gap reruns when coding.
    # Consistency beats per-token delta for a 17-iteration agent loop.
    # Restore per-tier model choice if/when a tier-aware routing layer
    # (orchestrator classifier) is in place to bound the blast radius.
    claude_default_model: str = "claude-sonnet-4-6"
    # Planning uses Opus 4.7 when running against Anthropic Direct so the
    # tier parity with bedrock_planning_model_id holds. This is the single
    # per-tier specialization we ship today — the orchestrator classifier
    # (Phase 1 T4 / Phase 2 T20) satisfies the "tier-aware routing layer"
    # precondition that the rest of the 3-constant block defers. Reviewer
    # and specialists remain on the default via their own model_setting_attr
    # overrides, so the blast radius of an Opus regression is scoped to
    # planning-heavy calls.
    claude_planning_model: str = "claude-opus-4-7"
    claude_fast_model: str = "claude-sonnet-4-6"
    # Output token cap. 4096 was too tight for coding runs — Sonnet 4.6's
    # final "open the PR, summarize, update Jira" turn routinely writes
    # >4k tokens (full PR body + tool calls) and got truncated mid-response,
    # which prevented github.create_pr from firing (see KAN-10, 2026-04-25).
    # 2026-05-08 (KAN-46 post-mortem): wide tickets where the CodingAgent
    # emits a single "implement all files in one batch" tool-use turn still
    # truncated at 16k. Sonnet 4.6/4.7 support 64k output tokens; bumping
    # the cap to 32k halves the "stop_reason=max_tokens" failure rate with
    # no wall-clock hit (request timeout still bounds the tail).
    claude_max_tokens: int = 32768
    # Coding runs that touch ≥10 files often need 2 iterations per file
    # (read → write) plus explore + plan + deliver. The KAN-12 post-mortem
    # first dropped this 50 → 25, but a follow-up KAN-12 re-run on the new
    # code showed the agent completing the full migration by iter 26 and
    # being blocked from github.create_pr only by the $3 cost ceiling —
    # iterations weren't the bottleneck, cost was. Bumped back to 50 so
    # wide tickets have headroom; claude_max_cost_per_task_usd is the
    # primary runaway-loop defense now.
    claude_max_tool_iterations: int = 50
    # Per-task hard budget ceiling. When cumulative Claude cost on a single
    # task hits this dollar value the agent loop short-circuits via
    # _should_continue and returns success=False with a budget_exceeded
    # reason. Prevents runaway $6+ incidents like KAN-12 (2026-04-30).
    claude_max_cost_per_task_usd: float = 3.0
    # Bedrock regional uplift on Claude pricing. us-east-1 is at parity
    # with Anthropic Direct; every other region carries a regional markup
    # (commonly ~1.10 for ap-southeast-1 as of 2026-04). Applied by
    # agents.base._iteration_cost_usd when claude_provider == bedrock and
    # aws_region != us-east-1. Override via env if the AWS price list
    # changes or you use reserved/provisioned throughput with different
    # economics.
    bedrock_price_multiplier: float = 1.10
    anthropic_api_key: str = ""
    # Phase 1 of docs/design/langgraph-migration.md: which implementation
    # backs create_provider(). "legacy" = the hand-rolled providers in
    # claude/provider.py; "langchain" = LangChainProvider. The provider
    # TYPE (anthropic vs bedrock) stays claude_provider — this flag only
    # selects the implementation behind the same interface.
    claude_provider_impl: Literal["legacy", "langchain"] = "legacy"

    # LangSmith tracing (Phase 0 of docs/design/langgraph-migration.md).
    # Disabled by default in every environment. Enabling requires BOTH
    # this flag AND the LANGSMITH_API_KEY env var (read by the langsmith
    # SDK directly — deliberately not a Settings field per LSMITH-05, so
    # the key cannot end up in dumps/logs). Masking is ON by default and
    # MUST stay on in prod/staging until the PII residency decision in
    # design §4 / LSMITH-08 is signed off.
    langsmith_enabled: bool = False
    langsmith_project: str = "ai-driven-dev"
    langsmith_mask_payloads: bool = True

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

    # Sandbox — only "docker" is supported. Legacy "mock" has been removed;
    # override via AI_DRIVEN_SANDBOX_MODE is accepted for forward-compat but
    # any value other than "docker" currently raises at factory time.
    sandbox_mode: str = "docker"

    # RAG
    rag_enabled: bool = False
    rag_auto_enable: bool = True
    rag_auto_enable_file_threshold: int = 500
    rag_chunk_size: int = 1000
    voyage_api_key: str = ""

    # Evaluation
    # Default ON — requires a real Claude provider (direct Anthropic or
    # Bedrock). Set to False if you want to disable Haiku grading entirely.
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
    sqs_endpoint_url: str | None = None  # localstack override; None = real AWS
    sqs_task_queue_url: str = ""
    sqs_review_queue_url: str = ""
    sqs_devops_queue_url: str = ""

    # SQS — orchestrator. Phase 2's _OrchestratorWrapper runs specialists
    # in-process on this queue; there is no separate specialists queue.
    sqs_orchestrator_queue_url: str = ""

    # SQS — QA post-coding worker (T22). Dedicated queue so QA's long
    # visibility timeout (1800s) and independent DLQ tuning don't affect
    # the other worker queues. When unset, the T22 coding-worker hook
    # skips QA enqueue (graceful — matches a pre-T22 deploy).
    sqs_qa_queue_url: str = ""

    # Orchestrator mode — controls whether the orchestrator's classification
    # affects downstream pipelines. Default 'shadow' for safe rollout.
    #   shadow: classify + write artifacts, but do NOT gate the existing
    #           coding/review pipeline (Phase 1 default).
    #   live:   gate the pipeline on specialist verdicts (Phase 2).
    #   off:    skip orchestrator entirely; tasks route as before.
    orchestrator_mode: str = "shadow"

    # When True, specialist verdict='error' rows are persisted for audit but
    # treated as verdict='pass' for downstream fan-out — i.e. the pipeline
    # does NOT halt on infrastructure failures. Default True per T-A1: even
    # after the max_output_tokens + prefill fixes drove the error rate below
    # 15%, a non-zero infra-flake baseline still exists (AWS credit
    # exhaustion, transient Bedrock/Anthropic 5xx) and we'd rather ship a
    # lenient default than halt a full_workflow because the Security
    # specialist hit a throttle. Downstream specialists that consumed the
    # errored role's artifact receive an empty prior_artifact for that role
    # (fan_out.py silently skips errored roles when this flag is True) and
    # continue. Every fallback activation emits
    # ``orchestrator.specialist.fallback_activated{role}`` as a DataDog
    # counter so the bypass is observable — without it this flag would
    # silently mask a creeping error-rate regression.
    #
    # Flip to False to restore strict semantics: error == block, pipeline
    # halts, task.status='blocked'. Useful for smoke tests + T0 reruns
    # where you want to measure the real specialist error rate without the
    # fallback masking it.
    orchestrator_treat_errors_as_pass: bool = True

    # HITL (Human-in-the-Loop) — specialists ask the user clarifying
    # questions mid-run via async continuation. See
    # docs/specs/hitl-v1.md. Default OFF — per-team opt-in via
    # ``teams.settings_json.hitl_enabled`` OR global env override.
    hitl_enabled: bool = False
    # Comma-separated list of roles allowed to ask. Start with
    # business_analyst only; expand after v1 burn-in.
    hitl_specialists: str = "business_analyst"
    # Per-task cap on clarification rounds. POST /respond refuses a
    # (rounds+1) answer once this is hit, and the specialist's next
    # turn is told to emit terminal verdict.
    hitl_max_rounds: int = 3
    # Reaper threshold — pending questions older than this many hours
    # get reaped to failed with a timeout audit row.
    hitl_awaiting_input_timeout_hours: int = 4
    # CodingAgent-specific kill switch. When False, CodingAgent keeps
    # its legacy polling-mode ask_clarification behavior (worker blocks
    # in asyncio.sleep until /respond writes the Redis key). Flip False
    # if the async-continuation path regresses a coding run; the BA
    # HITL flow is unaffected by this flag.
    hitl_coding_enabled: bool = True
    # Per-task clarification cap specifically for coding runs. Lower
    # than hitl_max_rounds (3) because coding context replay is more
    # expensive than specialist single-turn replay.
    hitl_coding_max_rounds: int = 2

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

    # SCM OAuth (per-user connection plumbing, PR 1 of the OAuth feature).
    # ``scm_token_key`` (dev) holds the current Fernet key as a url-safe
    # base64 string. ``scm_token_key_prior_keys`` is a list of older keys
    # accepted for decryption during rotation (MultiFernet). In prod,
    # ``scm_token_key_secret_arn`` takes precedence — the key lives in
    # AWS Secrets Manager. Empty-string defaults mean "no key loaded";
    # the OAuth flow raises at runtime rather than at import if a route
    # actually needs the key.
    scm_token_key: str = ""
    scm_token_key_prior_keys: list[str] = []
    scm_token_key_secret_arn: str = ""
    # OAuth App credentials — registered once per env with the provider.
    # See docs/runbooks/07-scm-oauth.md for registration steps.
    github_oauth_client_id: str = ""
    github_oauth_client_secret: SecretStr = SecretStr("")
    bitbucket_oauth_client_id: str = ""
    bitbucket_oauth_client_secret: SecretStr = SecretStr("")
    # Prepended to the callback path when building the authorize-redirect
    # URL. Dev: http://localhost:8888 . Prod: https://<domain>. Must be
    # registered as a callback URL on the OAuth App at the provider.
    scm_oauth_redirect_base: str = "http://localhost:8888"

    # DataDog
    dd_service: str = "ai-driven"
    dd_env: str = "local"
    dd_api_key: str = ""
    dd_app_key: str = ""
    datadog_enabled: bool = False


@lru_cache
def get_settings() -> Settings:
    return Settings()
