package http

import (
	"github.com/labstack/echo/v4"
	echomw "github.com/labstack/echo/v4/middleware"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent"
	"github.com/AirdropToTheMoon/ai-driven/internal/agent/guardrail"
	"github.com/AirdropToTheMoon/ai-driven/internal/config"
	"github.com/AirdropToTheMoon/ai-driven/internal/http/handler"
	mw "github.com/AirdropToTheMoon/ai-driven/internal/http/middleware"
	"github.com/AirdropToTheMoon/ai-driven/internal/repository"
)

// NewServer creates and configures the Echo HTTP server with all routes and middleware.
func NewServer(cfg *config.Config, opts ...ServerOption) *echo.Echo {
	o := &serverOptions{}
	for _, opt := range opts {
		opt(o)
	}

	e := echo.New()
	e.HideBanner = true

	e.Use(
		echomw.RequestID(),
		mw.RequestLogger(),
		echomw.Recover(),
		mw.CORS(),
	)

	e.GET("/health", handler.Health)

	if o.sqsClient != nil && cfg.AWS.SQSAgentQueueURL != "" {
		classifier := agent.NewCommentIntentClassifier(cfg.Agent.MentionKeyword)
		webhookHandler := handler.NewAgentWebhookHandler(
			classifier,
			o.rateLimiter,
			o.sqsClient,
			cfg.AWS.SQSAgentQueueURL,
			&cfg.Cost,
			&cfg.Agent,
		)

		jiraGroup := e.Group("/webhooks/jira")
		jiraGroup.Use(mw.JiraWebhook(func() string { return cfg.Jira.WebhookSecret }))
		jiraGroup.POST("/agent", webhookHandler.HandleJira)

		githubGroup := e.Group("/webhooks/github")
		githubGroup.Use(mw.GitHubWebhook(func() string { return "" }))
		githubGroup.POST("/agent", webhookHandler.HandleGitHub)
	}

	pipelineHandler := handler.NewPipelineHandler()
	pipeline := e.Group("/pipeline")
	pipeline.POST("/fetch-ticket", pipelineHandler.FetchTicket)
	pipeline.POST("/fetch-context", pipelineHandler.FetchContext)
	pipeline.POST("/invoke-ai", pipelineHandler.InvokeAI)
	pipeline.POST("/create-pr", pipelineHandler.CreatePR)
	pipeline.POST("/merge-wait", pipelineHandler.MergeWait)

	if o.approvalStore != nil {
		approvalHandler := handler.NewApprovalHandler(o.approvalStore)
		e.POST("/api/approvals/process", approvalHandler.ProcessApproval)
	}

	return e
}

type serverOptions struct {
	sqsClient     handler.SQSSender
	rateLimiter   *repository.DynamoRateLimiter
	approvalStore *guardrail.ApprovalStore
}

// ServerOption configures optional dependencies for the server.
type ServerOption func(*serverOptions)

// WithSQSClient sets the SQS client for webhook handlers.
func WithSQSClient(client handler.SQSSender) ServerOption {
	return func(o *serverOptions) { o.sqsClient = client }
}

// WithRateLimiter sets the rate limiter for webhook handlers.
func WithRateLimiter(rl *repository.DynamoRateLimiter) ServerOption {
	return func(o *serverOptions) { o.rateLimiter = rl }
}

// WithApprovalStore sets the approval store for the approval handler.
func WithApprovalStore(store *guardrail.ApprovalStore) ServerOption {
	return func(o *serverOptions) { o.approvalStore = store }
}
