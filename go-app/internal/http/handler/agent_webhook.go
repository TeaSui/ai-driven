package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strings"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/google/uuid"
	"github.com/labstack/echo/v4"
	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent"
	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
	"github.com/AirdropToTheMoon/ai-driven/internal/config"
	"github.com/AirdropToTheMoon/ai-driven/internal/repository"
	"github.com/AirdropToTheMoon/ai-driven/internal/security"
)

var ticketKeyRegex = regexp.MustCompile(`[A-Z][A-Z0-9]+-\d+`)

// SQSSender abstracts the SQS SendMessage operation.
type SQSSender interface {
	SendMessage(ctx context.Context, params *sqs.SendMessageInput, optFns ...func(*sqs.Options)) (*sqs.SendMessageOutput, error)
}

// AgentTask is the SQS message body for async agent processing.
type AgentTask struct {
	TicketKey     string            `json:"ticketKey"`
	Platform      string            `json:"platform"`
	CommentBody   string            `json:"commentBody"`
	CommentAuthor string            `json:"commentAuthor"`
	TenantID      string            `json:"tenantId"`
	AckCommentID  string            `json:"ackCommentId,omitempty"`
	PRContext     map[string]string `json:"prContext,omitempty"`
	Intent        string            `json:"intent"`
	CorrelationID string            `json:"correlationId"`
}

// AgentWebhookHandler processes webhook events and enqueues agent tasks.
type AgentWebhookHandler struct {
	classifier  *agent.CommentIntentClassifier
	rateLimiter *repository.DynamoRateLimiter
	sqsClient   SQSSender
	queueURL    string
	costCfg     *config.CostConfig
	agentCfg    *config.AgentConfig
}

// NewAgentWebhookHandler creates a new AgentWebhookHandler.
func NewAgentWebhookHandler(
	classifier *agent.CommentIntentClassifier,
	rateLimiter *repository.DynamoRateLimiter,
	sqsClient SQSSender,
	queueURL string,
	costCfg *config.CostConfig,
	agentCfg *config.AgentConfig,
) *AgentWebhookHandler {
	return &AgentWebhookHandler{
		classifier:  classifier,
		rateLimiter: rateLimiter,
		sqsClient:   sqsClient,
		queueURL:    queueURL,
		costCfg:     costCfg,
		agentCfg:    agentCfg,
	}
}

type jiraWebhookPayload struct {
	WebhookEvent string `json:"webhookEvent"`
	Comment      struct {
		Body   string `json:"body"`
		Author struct {
			DisplayName string `json:"displayName"`
			AccountID   string `json:"accountId"`
		} `json:"author"`
	} `json:"comment"`
	Issue struct {
		Key    string `json:"key"`
		Fields struct {
			Summary string `json:"summary"`
			Labels  []struct {
				Name string `json:"name"`
			} `json:"labels"`
		} `json:"fields"`
	} `json:"issue"`
}

type githubWebhookPayload struct {
	Action  string `json:"action"`
	Comment struct {
		Body string `json:"body"`
		User struct {
			Login string `json:"login"`
		} `json:"user"`
	} `json:"comment"`
	PullRequest struct {
		Title  string `json:"title"`
		Number int    `json:"number"`
		Head   struct {
			Ref string `json:"ref"`
		} `json:"head"`
	} `json:"pull_request"`
	Issue struct {
		Title       string `json:"title"`
		Number      int    `json:"number"`
		PullRequest *struct {
			URL string `json:"url"`
		} `json:"pull_request,omitempty"`
	} `json:"issue"`
	Repository struct {
		FullName string `json:"full_name"`
	} `json:"repository"`
}

// HandleJira processes Jira webhook events.
func (h *AgentWebhookHandler) HandleJira(c echo.Context) error {
	ctx := c.Request().Context()

	var payload jiraWebhookPayload
	if err := h.bindPayload(c, &payload); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid payload"})
	}

	ticketKey := payload.Issue.Key
	if err := security.ValidateTicketKey(ticketKey); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid ticket key"})
	}

	commentBody := payload.Comment.Body
	author := payload.Comment.Author.DisplayName
	authorID := payload.Comment.Author.AccountID
	isBot := authorID == h.agentCfg.BotAccountID

	intent := h.classifier.Classify(commentBody, isBot)
	if intent == model.IntentIrrelevant {
		return c.JSON(http.StatusOK, map[string]string{"status": "ignored"})
	}

	if err := h.rateLimit(ctx, ticketKey, author); err != nil {
		return c.JSON(http.StatusTooManyRequests, map[string]string{"error": "rate limit exceeded"})
	}

	sanitizedBody := security.SanitizeCommentBody(commentBody)
	sanitizedAuthor := security.SanitizeAuthor(author)

	task := &AgentTask{
		TicketKey:     ticketKey,
		Platform:      "jira",
		CommentBody:   sanitizedBody,
		CommentAuthor: sanitizedAuthor,
		TenantID:      extractTenantID(ticketKey),
		Intent:        string(intent),
		CorrelationID: uuid.New().String(),
	}

	if err := h.enqueue(ctx, task); err != nil {
		log.Ctx(ctx).Error().Err(err).Str("ticketKey", ticketKey).Msg("Failed to enqueue agent task")
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to enqueue task"})
	}

	return c.JSON(http.StatusAccepted, map[string]string{"status": "accepted", "ticketKey": ticketKey})
}

// HandleGitHub processes GitHub webhook events.
func (h *AgentWebhookHandler) HandleGitHub(c echo.Context) error {
	ctx := c.Request().Context()

	var payload githubWebhookPayload
	if err := h.bindPayload(c, &payload); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid payload"})
	}

	if payload.Action != "created" {
		return c.JSON(http.StatusOK, map[string]string{"status": "ignored"})
	}

	isPR := payload.PullRequest.Number > 0 || (payload.Issue.PullRequest != nil)
	if !isPR {
		return c.JSON(http.StatusOK, map[string]string{"status": "ignored", "reason": "not a PR comment"})
	}

	ticketKey := h.extractTicketKey(&payload)
	if ticketKey == "" {
		return c.JSON(http.StatusOK, map[string]string{"status": "ignored", "reason": "no ticket key found"})
	}

	commentBody := payload.Comment.Body
	author := payload.Comment.User.Login
	isBot := author == h.agentCfg.BotAccountID

	intent := h.classifier.Classify(commentBody, isBot)
	if intent == model.IntentIrrelevant {
		return c.JSON(http.StatusOK, map[string]string{"status": "ignored"})
	}

	if err := h.rateLimit(ctx, ticketKey, author); err != nil {
		return c.JSON(http.StatusTooManyRequests, map[string]string{"error": "rate limit exceeded"})
	}

	sanitizedBody := security.SanitizeCommentBody(commentBody)
	sanitizedAuthor := security.SanitizeAuthor(author)

	prContext := map[string]string{
		"repo":     payload.Repository.FullName,
		"prNumber": fmt.Sprintf("%d", h.prNumber(&payload)),
		"branch":   payload.PullRequest.Head.Ref,
	}

	task := &AgentTask{
		TicketKey:     ticketKey,
		Platform:      "github",
		CommentBody:   sanitizedBody,
		CommentAuthor: sanitizedAuthor,
		TenantID:      extractTenantID(ticketKey),
		PRContext:     prContext,
		Intent:        string(intent),
		CorrelationID: uuid.New().String(),
	}

	if err := h.enqueue(ctx, task); err != nil {
		log.Ctx(ctx).Error().Err(err).Str("ticketKey", ticketKey).Msg("Failed to enqueue agent task")
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to enqueue task"})
	}

	return c.JSON(http.StatusAccepted, map[string]string{"status": "accepted", "ticketKey": ticketKey})
}

func (h *AgentWebhookHandler) bindPayload(c echo.Context, v any) error {
	if raw, ok := c.Get("rawBody").([]byte); ok && len(raw) > 0 {
		return json.Unmarshal(raw, v)
	}
	return c.Bind(v)
}

func (h *AgentWebhookHandler) rateLimit(ctx context.Context, ticketKey, author string) error {
	if h.rateLimiter == nil {
		return nil
	}
	if err := h.rateLimiter.ConsumeOrThrow(ctx, "ticket:"+ticketKey, h.costCfg.MaxRequestsPerTicketHour); err != nil {
		if errors.Is(err, repository.ErrRateLimitExceeded) {
			return err
		}
	}
	if err := h.rateLimiter.ConsumeOrThrow(ctx, "user:"+author, h.costCfg.MaxRequestsPerUserHour); err != nil {
		if errors.Is(err, repository.ErrRateLimitExceeded) {
			return err
		}
	}
	return nil
}

func (h *AgentWebhookHandler) enqueue(ctx context.Context, task *AgentTask) error {
	body, err := json.Marshal(task)
	if err != nil {
		return fmt.Errorf("marshal agent task: %w", err)
	}

	dedupID := uuid.New().String()
	_, err = h.sqsClient.SendMessage(ctx, &sqs.SendMessageInput{
		QueueUrl:               aws.String(h.queueURL),
		MessageBody:            aws.String(string(body)),
		MessageGroupId:         aws.String(task.TicketKey),
		MessageDeduplicationId: aws.String(dedupID),
		MessageAttributes: map[string]sqstypes.MessageAttributeValue{
			"platform": {
				DataType:    aws.String("String"),
				StringValue: aws.String(task.Platform),
			},
		},
	})
	return err
}

func extractTenantID(ticketKey string) string {
	if idx := strings.Index(ticketKey, "-"); idx > 0 {
		return ticketKey[:idx]
	}
	return ticketKey
}

func (h *AgentWebhookHandler) extractTicketKey(payload *githubWebhookPayload) string {
	sources := []string{
		payload.PullRequest.Title,
		payload.PullRequest.Head.Ref,
		payload.Issue.Title,
	}
	for _, src := range sources {
		if match := ticketKeyRegex.FindString(src); match != "" {
			return match
		}
	}
	return ""
}

func (h *AgentWebhookHandler) prNumber(payload *githubWebhookPayload) int {
	if payload.PullRequest.Number > 0 {
		return payload.PullRequest.Number
	}
	return payload.Issue.Number
}
