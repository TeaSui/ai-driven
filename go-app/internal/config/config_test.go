package config

import (
	"os"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestLoad_Defaults(t *testing.T) {
	cfg := Load()
	require.NotNil(t, cfg)

	assert.Equal(t, "claude-sonnet-4-20250514", cfg.Claude.Model)
	assert.Equal(t, 4096, cfg.Claude.MaxTokens)
	assert.InDelta(t, 0.3, cfg.Claude.Temperature, 0.001)
	assert.Equal(t, "ANTHROPIC_API", cfg.Claude.Provider)
	assert.Equal(t, "us-east-1", cfg.Claude.BedrockRegion)
	assert.Equal(t, 200000, cfg.Claude.MaxContext)
	assert.Equal(t, 4096, cfg.Claude.ResearcherMaxToks)

	assert.Equal(t, "us-east-1", cfg.AWS.Region)

	assert.True(t, cfg.Agent.Enabled)
	assert.Equal(t, 10, cfg.Agent.MaxTurns)
	assert.Equal(t, 720, cfg.Agent.MaxWallClockSeconds)
	assert.Equal(t, "@ai", cfg.Agent.TriggerPrefix)
	assert.Equal(t, 100000, cfg.Agent.TokenBudget)
	assert.Equal(t, 10, cfg.Agent.RecentMessagesToKeep)
	assert.False(t, cfg.Agent.GuardrailsEnabled)
	assert.InDelta(t, 100.0, cfg.Agent.CostBudgetPerTicket, 0.001)
	assert.True(t, cfg.Agent.ClassifierUseLLM)
	assert.Equal(t, "ai", cfg.Agent.MentionKeyword)

	assert.True(t, cfg.Slack.FallbackToJira)

	assert.False(t, cfg.Cost.AwareMode)
	assert.InDelta(t, 100.0, cfg.Cost.MonthlyBudgetUSD, 0.001)
	assert.Equal(t, 500000, cfg.Cost.MaxTokensPerTicket)
	assert.Equal(t, 60, cfg.Cost.MaxRequestsPerUserHour)
	assert.Equal(t, 30, cfg.Cost.MaxRequestsPerTicketHour)

	assert.Equal(t, "INCREMENTAL", cfg.Context.Mode)
	assert.Equal(t, 50000, cfg.Context.MaxFileSizeChars)
	assert.Equal(t, 200000, cfg.Context.MaxTotalChars)
	assert.Equal(t, 500000, cfg.Context.MaxFileSizeBytes)
	assert.Equal(t, 30000, cfg.Context.SummarizationThreshold)
	assert.Equal(t, "ai-driven/", cfg.Context.BranchPrefix)

	assert.False(t, cfg.MCP.GatewayEnabled)
}

func TestLoad_EnvOverrides(t *testing.T) {
	envs := map[string]string{
		"JIRA_SECRET_ARN":      "arn:aws:secretsmanager:us-east-1:123:secret:jira",
		"JIRA_WEBHOOK_SECRET":  "jira-secret-token",
		"GITHUB_SECRET_ARN":    "arn:aws:secretsmanager:us-east-1:123:secret:github",
		"BITBUCKET_SECRET_ARN": "arn:aws:secretsmanager:us-east-1:123:secret:bb",
		"CLAUDE_MODEL":         "claude-opus-4-20250514",
		"CLAUDE_MAX_TOKENS":    "8192",
		"CLAUDE_TEMPERATURE":   "0.7",
		"AWS_REGION":           "eu-west-1",
		"SQS_AGENT_QUEUE_URL":  "https://sqs.eu-west-1.amazonaws.com/123/queue",
		"DYNAMODB_STATE_TABLE": "agent-state",
		"AGENT_ENABLED":        "false",
		"AGENT_MAX_TURNS":      "20",
		"SLACK_WEBHOOK_URL":    "https://hooks.slack.com/services/xxx",
		"COST_AWARE_MODE":      "true",
		"MONTHLY_BUDGET_USD":   "500.0",
		"CONTEXT_MODE":         "FULL",
		"MCP_GATEWAY_ENABLED":  "true",
		"MCP_GATEWAY_URL":      "http://localhost:8080",
		"AGENT_BOT_ACCOUNT_ID": "bot-123",
	}

	for k, v := range envs {
		t.Setenv(k, v)
	}

	cfg := Load()

	assert.Equal(t, "arn:aws:secretsmanager:us-east-1:123:secret:jira", cfg.Jira.SecretARN)
	assert.Equal(t, "jira-secret-token", cfg.Jira.WebhookSecret)
	assert.Equal(t, "arn:aws:secretsmanager:us-east-1:123:secret:github", cfg.GitHub.SecretARN)
	assert.Equal(t, "arn:aws:secretsmanager:us-east-1:123:secret:bb", cfg.Bitbucket.SecretARN)
	assert.Equal(t, "claude-opus-4-20250514", cfg.Claude.Model)
	assert.Equal(t, 8192, cfg.Claude.MaxTokens)
	assert.InDelta(t, 0.7, cfg.Claude.Temperature, 0.001)
	assert.Equal(t, "eu-west-1", cfg.AWS.Region)
	assert.Equal(t, "https://sqs.eu-west-1.amazonaws.com/123/queue", cfg.AWS.SQSAgentQueueURL)
	assert.Equal(t, "agent-state", cfg.AWS.DynamoDBStateTable)
	assert.False(t, cfg.Agent.Enabled)
	assert.Equal(t, 20, cfg.Agent.MaxTurns)
	assert.Equal(t, "https://hooks.slack.com/services/xxx", cfg.Slack.WebhookURL)
	assert.True(t, cfg.Cost.AwareMode)
	assert.InDelta(t, 500.0, cfg.Cost.MonthlyBudgetUSD, 0.001)
	assert.Equal(t, "FULL", cfg.Context.Mode)
	assert.True(t, cfg.MCP.GatewayEnabled)
	assert.Equal(t, "http://localhost:8080", cfg.MCP.GatewayURL)
	assert.Equal(t, "bot-123", cfg.Agent.BotAccountID)
}

func TestLoad_EmptyEnvUsesDefaults(t *testing.T) {
	// Ensure specific env vars are unset
	os.Unsetenv("CLAUDE_MODEL")
	os.Unsetenv("AGENT_ENABLED")

	cfg := Load()
	assert.Equal(t, "claude-sonnet-4-20250514", cfg.Claude.Model)
	assert.True(t, cfg.Agent.Enabled)
}
