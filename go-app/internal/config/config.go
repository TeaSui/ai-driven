package config

import (
	"strings"

	"github.com/spf13/viper"
)

// JiraConfig holds Jira-related configuration.
type JiraConfig struct {
	SecretARN        string
	WebhookSecretARN string
	WebhookSecret    string
}

// GitHubConfig holds GitHub-related configuration.
type GitHubConfig struct {
	SecretARN        string
	WebhookSecretARN string
}

// BitbucketConfig holds Bitbucket-related configuration.
type BitbucketConfig struct {
	SecretARN string
}

// ClaudeConfig holds Claude AI model configuration.
type ClaudeConfig struct {
	SecretARN         string
	Model             string
	ModelFallback     string
	MaxTokens         int
	Temperature       float64
	PromptVersion     string
	ResearcherModel   string
	ResearcherMaxToks int
	Provider          string
	BedrockRegion     string
	MaxContext        int
}

// AWSConfig holds AWS infrastructure configuration.
type AWSConfig struct {
	Region                       string
	SQSAgentQueueURL             string
	DynamoDBStateTable           string
	S3ContextBucket              string
	S3AuditBucket                string
	StepFunctionsStateMachineARN string
}

// AgentConfig holds agent behavior configuration.
type AgentConfig struct {
	Enabled              bool
	MaxTurns             int
	MaxWallClockSeconds  int
	TriggerPrefix        string
	TokenBudget          int
	RecentMessagesToKeep int
	GuardrailsEnabled    bool
	CostBudgetPerTicket  float64
	ClassifierUseLLM     bool
	MentionKeyword       string
	BotAccountID         string
}

// SlackConfig holds Slack notification configuration.
type SlackConfig struct {
	WebhookURL     string
	Channel        string
	FallbackToJira bool
}

// CostConfig holds cost control configuration.
type CostConfig struct {
	AwareMode                bool
	MonthlyBudgetUSD         float64
	MaxTokensPerTicket       int
	MaxRequestsPerUserHour   int
	MaxRequestsPerTicketHour int
}

// ContextConfig holds context management configuration.
type ContextConfig struct {
	Mode                   string
	MaxFileSizeChars       int
	MaxTotalChars          int
	MaxFileSizeBytes       int
	SummarizationThreshold int
	BranchPrefix           string
	DefaultPlatform        string
	DefaultWorkspace       string
	DefaultRepo            string
}

// MCPConfig holds MCP gateway configuration.
type MCPConfig struct {
	GatewayEnabled bool
	GatewayURL     string
	ServersConfig  string
}

// Config is the top-level application configuration.
type Config struct {
	Jira      JiraConfig
	GitHub    GitHubConfig
	Bitbucket BitbucketConfig
	Claude    ClaudeConfig
	AWS       AWSConfig
	Agent     AgentConfig
	Slack     SlackConfig
	Cost      CostConfig
	Context   ContextConfig
	MCP       MCPConfig
}

// Load reads configuration from environment variables using Viper and returns a Config.
func Load() *Config {
	v := viper.New()
	v.AutomaticEnv()
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))

	// Claude defaults
	v.SetDefault("CLAUDE_MODEL", "claude-sonnet-4-20250514")
	v.SetDefault("CLAUDE_MAX_TOKENS", 4096)
	v.SetDefault("CLAUDE_TEMPERATURE", 0.3)
	v.SetDefault("CLAUDE_RESEARCHER_MAX_TOKENS", 4096)
	v.SetDefault("CLAUDE_PROVIDER", "ANTHROPIC_API")
	v.SetDefault("CLAUDE_BEDROCK_REGION", "us-east-1")
	v.SetDefault("CLAUDE_MAX_CONTEXT", 200000)

	// AWS defaults
	v.SetDefault("AWS_REGION", "us-east-1")

	// Agent defaults
	v.SetDefault("AGENT_ENABLED", true)
	v.SetDefault("AGENT_MAX_TURNS", 10)
	v.SetDefault("AGENT_MAX_WALL_CLOCK_SECONDS", 720)
	v.SetDefault("AGENT_TRIGGER_PREFIX", "@ai")
	v.SetDefault("AGENT_TOKEN_BUDGET", 100000)
	v.SetDefault("AGENT_RECENT_MESSAGES_TO_KEEP", 10)
	v.SetDefault("AGENT_GUARDRAILS_ENABLED", false)
	v.SetDefault("AGENT_COST_BUDGET_PER_TICKET", 100)
	v.SetDefault("AGENT_CLASSIFIER_USE_LLM", true)
	v.SetDefault("AGENT_MENTION_KEYWORD", "ai")

	// Slack defaults
	v.SetDefault("SLACK_FALLBACK_TO_JIRA", true)

	// Cost defaults
	v.SetDefault("COST_AWARE_MODE", false)
	v.SetDefault("MONTHLY_BUDGET_USD", 100.0)
	v.SetDefault("MAX_TOKENS_PER_TICKET", 500000)
	v.SetDefault("MAX_REQUESTS_PER_USER_PER_HOUR", 60)
	v.SetDefault("MAX_REQUESTS_PER_TICKET_PER_HOUR", 30)

	// Context defaults
	v.SetDefault("CONTEXT_MODE", "INCREMENTAL")
	v.SetDefault("CONTEXT_MAX_FILE_SIZE_CHARS", 50000)
	v.SetDefault("CONTEXT_MAX_TOTAL_CHARS", 200000)
	v.SetDefault("CONTEXT_MAX_FILE_SIZE_BYTES", 500000)
	v.SetDefault("CONTEXT_SUMMARIZATION_THRESHOLD", 30000)
	v.SetDefault("CONTEXT_BRANCH_PREFIX", "ai-driven/")

	// MCP defaults
	v.SetDefault("MCP_GATEWAY_ENABLED", false)

	return &Config{
		Jira: JiraConfig{
			SecretARN:        v.GetString("JIRA_SECRET_ARN"),
			WebhookSecretARN: v.GetString("JIRA_WEBHOOK_SECRET_ARN"),
			WebhookSecret:    v.GetString("JIRA_WEBHOOK_SECRET"),
		},
		GitHub: GitHubConfig{
			SecretARN:        v.GetString("GITHUB_SECRET_ARN"),
			WebhookSecretARN: v.GetString("GITHUB_WEBHOOK_SECRET_ARN"),
		},
		Bitbucket: BitbucketConfig{
			SecretARN: v.GetString("BITBUCKET_SECRET_ARN"),
		},
		Claude: ClaudeConfig{
			SecretARN:         v.GetString("CLAUDE_SECRET_ARN"),
			Model:             v.GetString("CLAUDE_MODEL"),
			ModelFallback:     v.GetString("CLAUDE_MODEL_FALLBACK"),
			MaxTokens:         v.GetInt("CLAUDE_MAX_TOKENS"),
			Temperature:       v.GetFloat64("CLAUDE_TEMPERATURE"),
			PromptVersion:     v.GetString("CLAUDE_PROMPT_VERSION"),
			ResearcherModel:   v.GetString("CLAUDE_RESEARCHER_MODEL"),
			ResearcherMaxToks: v.GetInt("CLAUDE_RESEARCHER_MAX_TOKENS"),
			Provider:          v.GetString("CLAUDE_PROVIDER"),
			BedrockRegion:     v.GetString("CLAUDE_BEDROCK_REGION"),
			MaxContext:        v.GetInt("CLAUDE_MAX_CONTEXT"),
		},
		AWS: AWSConfig{
			Region:                       v.GetString("AWS_REGION"),
			SQSAgentQueueURL:             v.GetString("SQS_AGENT_QUEUE_URL"),
			DynamoDBStateTable:           v.GetString("DYNAMODB_STATE_TABLE"),
			S3ContextBucket:              v.GetString("S3_CONTEXT_BUCKET"),
			S3AuditBucket:                v.GetString("S3_AUDIT_BUCKET"),
			StepFunctionsStateMachineARN: v.GetString("STEP_FUNCTIONS_STATE_MACHINE_ARN"),
		},
		Agent: AgentConfig{
			Enabled:              v.GetBool("AGENT_ENABLED"),
			MaxTurns:             v.GetInt("AGENT_MAX_TURNS"),
			MaxWallClockSeconds:  v.GetInt("AGENT_MAX_WALL_CLOCK_SECONDS"),
			TriggerPrefix:        v.GetString("AGENT_TRIGGER_PREFIX"),
			TokenBudget:          v.GetInt("AGENT_TOKEN_BUDGET"),
			RecentMessagesToKeep: v.GetInt("AGENT_RECENT_MESSAGES_TO_KEEP"),
			GuardrailsEnabled:    v.GetBool("AGENT_GUARDRAILS_ENABLED"),
			CostBudgetPerTicket:  v.GetFloat64("AGENT_COST_BUDGET_PER_TICKET"),
			ClassifierUseLLM:     v.GetBool("AGENT_CLASSIFIER_USE_LLM"),
			MentionKeyword:       v.GetString("AGENT_MENTION_KEYWORD"),
			BotAccountID:         v.GetString("AGENT_BOT_ACCOUNT_ID"),
		},
		Slack: SlackConfig{
			WebhookURL:     v.GetString("SLACK_WEBHOOK_URL"),
			Channel:        v.GetString("SLACK_CHANNEL"),
			FallbackToJira: v.GetBool("SLACK_FALLBACK_TO_JIRA"),
		},
		Cost: CostConfig{
			AwareMode:                v.GetBool("COST_AWARE_MODE"),
			MonthlyBudgetUSD:         v.GetFloat64("MONTHLY_BUDGET_USD"),
			MaxTokensPerTicket:       v.GetInt("MAX_TOKENS_PER_TICKET"),
			MaxRequestsPerUserHour:   v.GetInt("MAX_REQUESTS_PER_USER_PER_HOUR"),
			MaxRequestsPerTicketHour: v.GetInt("MAX_REQUESTS_PER_TICKET_PER_HOUR"),
		},
		Context: ContextConfig{
			Mode:                   v.GetString("CONTEXT_MODE"),
			MaxFileSizeChars:       v.GetInt("CONTEXT_MAX_FILE_SIZE_CHARS"),
			MaxTotalChars:          v.GetInt("CONTEXT_MAX_TOTAL_CHARS"),
			MaxFileSizeBytes:       v.GetInt("CONTEXT_MAX_FILE_SIZE_BYTES"),
			SummarizationThreshold: v.GetInt("CONTEXT_SUMMARIZATION_THRESHOLD"),
			BranchPrefix:           v.GetString("CONTEXT_BRANCH_PREFIX"),
			DefaultPlatform:        v.GetString("CONTEXT_DEFAULT_PLATFORM"),
			DefaultWorkspace:       v.GetString("CONTEXT_DEFAULT_WORKSPACE"),
			DefaultRepo:            v.GetString("CONTEXT_DEFAULT_REPO"),
		},
		MCP: MCPConfig{
			GatewayEnabled: v.GetBool("MCP_GATEWAY_ENABLED"),
			GatewayURL:     v.GetString("MCP_GATEWAY_URL"),
			ServersConfig:  v.GetString("MCP_SERVERS_CONFIG"),
		},
	}
}
