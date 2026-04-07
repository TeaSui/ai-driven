package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/bedrockruntime"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/secretsmanager"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent"
	"github.com/AirdropToTheMoon/ai-driven/internal/agent/guardrail"
	"github.com/AirdropToTheMoon/ai-driven/internal/agent/tool"
	"github.com/AirdropToTheMoon/ai-driven/internal/claude"
	"github.com/AirdropToTheMoon/ai-driven/internal/config"
	apphttp "github.com/AirdropToTheMoon/ai-driven/internal/http"
	"github.com/AirdropToTheMoon/ai-driven/internal/http/sqslistener"
	"github.com/AirdropToTheMoon/ai-driven/internal/mcp"
	"github.com/AirdropToTheMoon/ai-driven/internal/notification"
	"github.com/AirdropToTheMoon/ai-driven/internal/provider/jira"
	"github.com/AirdropToTheMoon/ai-driven/internal/repository"
	"github.com/AirdropToTheMoon/ai-driven/internal/secrets"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// Build-time variables injected via ldflags.
var (
	version   = "dev"
	buildTime = "unknown"
)

func main() {
	// ── Load configuration ────────────────────────────────────────────
	cfg := config.Load()

	// ── Initialize structured logging ─────────────────────────────────
	initLogger(cfg)
	log.Info().
		Str("version", version).
		Str("buildTime", buildTime).
		Msg("starting ai-driven server")

	// ── AWS SDK clients ───────────────────────────────────────────────
	ctx := context.Background()
	awsCfg, err := awsconfig.LoadDefaultConfig(ctx, awsconfig.WithRegion(cfg.AWS.Region))
	if err != nil {
		log.Fatal().Err(err).Msg("failed to load AWS config")
	}

	dynamoClient := dynamodb.NewFromConfig(awsCfg)
	sqsClient := sqs.NewFromConfig(awsCfg)
	s3Client := s3.NewFromConfig(awsCfg)
	smClient := secretsmanager.NewFromConfig(awsCfg)

	// ── Secrets resolution ────────────────────────────────────────────
	resolver := secrets.NewResolver(smClient)

	// ── Repositories ──────────────────────────────────────────────────
	rateLimiter := repository.NewDynamoRateLimiter(dynamoClient, cfg.AWS.DynamoDBStateTable)
	conversationRepo := repository.NewDynamoConversationRepository(dynamoClient, cfg.AWS.DynamoDBStateTable)
	_ = repository.NewTicketStateRepository(dynamoClient, cfg.AWS.DynamoDBStateTable)
	auditService := repository.NewAuditService(s3Client, cfg.AWS.S3AuditBucket)
	_ = auditService // audit hook integration deferred to next iteration

	// ── AI client ─────────────────────────────────────────────────────
	aiClient := createAIClient(cfg, &awsCfg, resolver)

	// ── Tool registry ─────────────────────────────────────────────────
	toolRegistry := tool.NewRegistry()

	// Register MCP gateway tools if enabled.
	if cfg.MCP.GatewayEnabled && cfg.MCP.GatewayURL != "" {
		mcpClients := mcp.CreateAllClients(cfg.MCP.GatewayURL)
		for _, mc := range mcpClients {
			toolRegistry.Register(mc)
		}
		log.Info().Int("namespaces", len(mcpClients)).Msg("MCP gateway tools registered")
	}

	// ── Guardrails ────────────────────────────────────────────────────
	riskRegistry := guardrail.NewToolRiskRegistry()
	approvalStore := guardrail.NewApprovalStore(dynamoClient, cfg.AWS.DynamoDBStateTable)

	var approvalNotifier spi.ApprovalNotifier
	if cfg.Slack.WebhookURL != "" {
		approvalNotifier = notification.NewSlackNotifier(cfg.Slack.WebhookURL, cfg.Slack.Channel)
		log.Info().Msg("Slack approval notifier configured")
	}

	guardedRegistry := guardrail.NewGuardedRegistry(
		riskRegistry, approvalStore, approvalNotifier, cfg.Agent.GuardrailsEnabled,
	)

	// ── Cost tracker ──────────────────────────────────────────────────
	costTracker := agent.NewDynamoCostTracker(dynamoClient, cfg.AWS.DynamoDBStateTable, cfg.Cost.MaxTokensPerTicket)

	// ── Conversation window manager ───────────────────────────────────
	windowManager := agent.NewConversationWindowManager(
		conversationRepo,
		cfg.Agent.TokenBudget,
		cfg.Agent.RecentMessagesToKeep,
	)

	// ── Agent orchestrator ────────────────────────────────────────────
	orchestrator := agent.NewOrchestrator(
		aiClient,
		agent.WithWindowManager(windowManager),
		agent.WithToolRegistry(toolRegistry),
		agent.WithGuardedRegistry(guardedRegistry),
		agent.WithMaxTurns(cfg.Agent.MaxTurns),
		agent.WithCostTracker(costTracker),
	)

	// ── Jira client (used by SQS listener to post response comments) ─
	var jiraClient *jira.Client
	jiraSecret, err := resolver.ResolveJira(ctx, cfg.Jira.SecretARN)
	if err != nil {
		log.Warn().Err(err).Msg("failed to resolve Jira credentials, comment posting disabled")
	} else if jiraSecret != nil {
		jiraClient = jira.NewClient(jiraSecret.BaseURL, jiraSecret.Email, jiraSecret.APIToken)
		log.Info().Str("baseURL", jiraSecret.BaseURL).Msg("Jira client initialized")
	}

	// ── HTTP server ───────────────────────────────────────────────────
	serverOpts := []apphttp.ServerOption{
		apphttp.WithSQSClient(sqsClient),
		apphttp.WithRateLimiter(rateLimiter),
		apphttp.WithApprovalStore(approvalStore),
	}
	e := apphttp.NewServer(cfg, serverOpts...)

	// ── SQS listener ─────────────────────────────────────────────────
	var listener *sqslistener.Listener
	if cfg.AWS.SQSAgentQueueURL != "" {
		listener = sqslistener.NewListener(sqsClient, cfg.AWS.SQSAgentQueueURL, orchestrator, jiraClient)
		listener.Start(ctx)
		log.Info().Str("queueURL", cfg.AWS.SQSAgentQueueURL).Msg("SQS listener started")
	}

	// ── Start HTTP server ────────────────────────────────────────────
	go func() {
		if err := e.Start(":8080"); err != nil {
			log.Info().Err(err).Msg("HTTP server stopped")
		}
	}()

	// ── Graceful shutdown ────────────────────────────────────────────
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, os.Interrupt, syscall.SIGTERM)
	<-quit
	log.Info().Msg("received shutdown signal")

	if listener != nil {
		listener.Stop()
		log.Info().Msg("SQS listener stopped")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)

	if err := e.Shutdown(shutdownCtx); err != nil {
		cancel()
		log.Error().Err(err).Msg("forced HTTP server shutdown")
		os.Exit(1)
	}
	cancel()

	log.Info().Msg("server stopped gracefully")
}

// initLogger configures zerolog based on the environment. When ENVIRONMENT is
// "production" (or unset), output is structured JSON; otherwise pretty console.
func initLogger(cfg *config.Config) {
	zerolog.TimeFieldFormat = zerolog.TimeFormatUnix

	env := os.Getenv("ENVIRONMENT")
	if env == "" || env == "production" {
		log.Logger = zerolog.New(os.Stdout).With().
			Timestamp().
			Caller().
			Str("version", version).
			Logger()
		return
	}

	// Development: human-readable console output.
	log.Logger = zerolog.New(zerolog.ConsoleWriter{Out: os.Stdout, TimeFormat: time.RFC3339}).
		With().
		Timestamp().
		Caller().
		Logger()

	_ = cfg // reserved for future logger config knobs
}

// createAIClient builds the appropriate AI client adapter based on provider config.
func createAIClient(cfg *config.Config, awsCfg *aws.Config, resolver *secrets.Resolver) agent.AiClient {
	switch cfg.Claude.Provider {
	case "BEDROCK":
		bedrockClient := bedrockruntime.NewFromConfig(*awsCfg, func(o *bedrockruntime.Options) {
			if cfg.Claude.BedrockRegion != "" {
				o.Region = cfg.Claude.BedrockRegion
			}
		})
		bc := claude.NewBedrockClient(
			bedrockClient,
			cfg.Claude.Model,
			cfg.Claude.MaxTokens,
			cfg.Claude.Temperature,
		)
		return claude.NewBedrockAdapter(bc)

	default: // "ANTHROPIC_API" or anything else
		apiKey := resolveAPIKey(cfg, resolver)
		c := claude.NewClient(apiKey, cfg.Claude.Model, cfg.Claude.MaxTokens, cfg.Claude.Temperature)
		return claude.NewClientAdapter(c)
	}
}

// resolveAPIKey attempts to fetch the Claude API key from Secrets Manager,
// falling back to the ANTHROPIC_API_KEY environment variable.
func resolveAPIKey(cfg *config.Config, resolver *secrets.Resolver) string {
	if cfg.Claude.SecretARN != "" {
		key, err := resolver.ResolveString(context.Background(), cfg.Claude.SecretARN)
		if err != nil {
			log.Warn().Err(err).Msg("failed to resolve Claude API key from Secrets Manager, falling back to env var")
		} else if key != "" {
			log.Info().Msg("Claude API key resolved from Secrets Manager")
			return key
		}
	}

	apiKey := os.Getenv("ANTHROPIC_API_KEY")
	if apiKey == "" {
		log.Warn().Msg("no Claude API key configured (neither Secrets Manager nor ANTHROPIC_API_KEY env var)")
	}
	return apiKey
}
