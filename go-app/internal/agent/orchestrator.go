package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/guardrail"
	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
	"github.com/AirdropToTheMoon/ai-driven/internal/agent/tool"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

const DefaultMaxTurns = 10

type CostTracker interface {
	HasRemainingBudget(ticketKey string) bool
	AddTokens(ticketKey string, tokens int)
}

type ProgressTracker interface {
	UpdateProgress(commentID string)
	Complete(commentID, finalResponse string)
	Fail(commentID, errorMessage string)
}

type WorkflowContextProvider interface {
	GetContextByKey(tenantID, ticketKey string) *WorkflowContext
}

// Orchestrator runs the ReAct (Reason + Act) loop.
type Orchestrator struct {
	aiClient            AiClient
	windowManager       *ConversationWindowManager
	costTracker         CostTracker
	guardedRegistry     *guardrail.GuardedRegistry
	toolRegistry        *tool.Registry
	progressTracker     ProgressTracker
	workflowCtxProvider WorkflowContextProvider
	maxTurns            int
}

func NewOrchestrator(
	aiClient AiClient,
	opts ...OrchestratorOption,
) *Orchestrator {
	o := &Orchestrator{
		aiClient: aiClient,
		maxTurns: DefaultMaxTurns,
	}
	for _, opt := range opts {
		opt(o)
	}
	return o
}

type OrchestratorOption func(*Orchestrator)

func WithWindowManager(wm *ConversationWindowManager) OrchestratorOption {
	return func(o *Orchestrator) { o.windowManager = wm }
}

func WithCostTracker(ct CostTracker) OrchestratorOption {
	return func(o *Orchestrator) { o.costTracker = ct }
}

func WithGuardedRegistry(gr *guardrail.GuardedRegistry) OrchestratorOption {
	return func(o *Orchestrator) { o.guardedRegistry = gr }
}

func WithToolRegistry(tr *tool.Registry) OrchestratorOption {
	return func(o *Orchestrator) { o.toolRegistry = tr }
}

func WithProgressTracker(pt ProgressTracker) OrchestratorOption {
	return func(o *Orchestrator) { o.progressTracker = pt }
}

func WithWorkflowContextProvider(wcp WorkflowContextProvider) OrchestratorOption {
	return func(o *Orchestrator) { o.workflowCtxProvider = wcp }
}

func WithMaxTurns(n int) OrchestratorOption {
	return func(o *Orchestrator) { o.maxTurns = n }
}

func (o *Orchestrator) Process(ctx context.Context, request *model.AgentRequest, intent model.CommentIntent) (*model.AgentResponse, error) {
	logger := log.Ctx(ctx).With().
		Str("ticketKey", request.TicketKey).
		Str("intent", string(intent)).
		Logger()

	// Budget check
	if o.costTracker != nil && !o.costTracker.HasRemainingBudget(request.TicketKey) {
		logger.Warn().Msg("Budget exhausted for ticket")
		return &model.AgentResponse{
			Text: "Budget exhausted for this ticket. Please contact your administrator.",
		}, nil
	}

	// Build system prompt
	var workflowCtx *WorkflowContext
	if o.workflowCtxProvider != nil {
		workflowCtx = o.workflowCtxProvider.GetContextByKey(request.Context.TenantID, request.TicketKey)
	}

	var tools []tool.Tool
	var toolSchemas []map[string]any
	if o.toolRegistry != nil {
		tools = o.toolRegistry.GetAllToolDefinitions()
		toolSchemas = make([]map[string]any, len(tools))
		for i, t := range tools {
			toolSchemas[i] = t.ToAPIFormat()
		}
	}

	promptBuilder := NewSystemPromptBuilder()
	promptBuilder.AppendPersona().
		AppendContext(request).
		AppendIntentGuidelines(intent)
	if workflowCtx != nil {
		promptBuilder.WithWorkflowContext(workflowCtx)
	}
	systemPrompt := promptBuilder.Build()

	// Initialize conversation
	messages, err := o.initConversation(ctx, request)
	if err != nil {
		return nil, fmt.Errorf("init conversation: %w", err)
	}

	var (
		totalTokens int
		toolsUsed   []string
		turnCount   int
	)

	// ReAct loop
	for turnCount < o.maxTurns {
		turnCount++

		if o.progressTracker != nil && request.AckCommentID != "" {
			o.progressTracker.UpdateProgress(request.AckCommentID)
		}

		logger.Info().Int("turn", turnCount).Msg("Starting ReAct turn")

		response, err := o.aiClient.ChatWithTools(ctx, systemPrompt, messages, toolSchemas)
		if err != nil {
			if o.progressTracker != nil && request.AckCommentID != "" {
				o.progressTracker.Fail(request.AckCommentID, "AI call failed")
			}
			return nil, fmt.Errorf("ai chat (turn %d): %w", turnCount, err)
		}

		totalTokens += response.TotalTokens()

		if o.costTracker != nil {
			o.costTracker.AddTokens(request.TicketKey, response.TotalTokens())
		}

		// Append assistant message
		assistantMsg := createAssistantMessage(request.TicketKey, request.Context.TenantID, response.ContentBlocks, response.TotalTokens())
		messages = o.appendMessage(ctx, request, messages, assistantMsg)

		if !response.HasToolUse() {
			// Final text response
			text := response.Text()
			if o.progressTracker != nil && request.AckCommentID != "" {
				o.progressTracker.Complete(request.AckCommentID, text)
			}

			return &model.AgentResponse{
				Text:       text,
				ToolsUsed:  toolsUsed,
				TokenCount: totalTokens,
				TurnCount:  turnCount,
			}, nil
		}

		// Extract and execute tool calls
		toolCalls, err := extractToolCalls(response.ContentBlocks)
		if err != nil {
			logger.Error().Err(err).Msg("Failed to extract tool calls")
			return nil, fmt.Errorf("extract tool calls: %w", err)
		}

		var toolResults []map[string]any
		for _, tc := range toolCalls {
			toolsUsed = append(toolsUsed, tc.Name)
			logger.Info().
				Str("tool", tc.Name).
				Str("toolCallID", tc.ID).
				Msg("Executing tool")

			result := o.executeTool(ctx, request, tc)
			toolResults = append(toolResults, result.ToContentBlock())
		}

		// Append tool results as user message
		toolResultMsg := createToolResultMessage(request.TicketKey, request.Context.TenantID, toolResults)
		messages = o.appendMessage(ctx, request, messages, toolResultMsg)
	}

	// Max turns reached
	logger.Warn().Int("maxTurns", o.maxTurns).Msg("Max turns reached")
	maxTurnsText := fmt.Sprintf("I've reached the maximum number of turns (%d) for this request. Here's what I've accomplished so far with the tools: %s",
		o.maxTurns, strings.Join(toolsUsed, ", "))

	if o.progressTracker != nil && request.AckCommentID != "" {
		o.progressTracker.Complete(request.AckCommentID, maxTurnsText)
	}

	return &model.AgentResponse{
		Text:       maxTurnsText,
		ToolsUsed:  toolsUsed,
		TokenCount: totalTokens,
		TurnCount:  turnCount,
	}, nil
}

func (o *Orchestrator) initConversation(ctx context.Context, request *model.AgentRequest) ([]map[string]any, error) {
	userMsg := createUserMessage(request.TicketKey, request.Context.TenantID, request.CommentBody, request.CommentAuthor)

	if o.windowManager != nil {
		return o.windowManager.AppendAndBuild(ctx, request.Context.TenantID, request.TicketKey, userMsg)
	}

	// In-memory mode
	contentJSON, err := json.Marshal([]map[string]any{
		{"type": "text", "text": request.CommentBody},
	})
	if err != nil {
		contentJSON = []byte("[]")
	}
	return []map[string]any{
		{"role": "user", "content": json.RawMessage(contentJSON)},
	}, nil
}

func (o *Orchestrator) appendMessage(ctx context.Context, request *model.AgentRequest, messages []map[string]any, msg *model.ConversationMessage) []map[string]any {
	if o.windowManager != nil {
		updated, err := o.windowManager.AppendAndBuild(ctx, request.Context.TenantID, request.TicketKey, msg)
		if err != nil {
			log.Ctx(ctx).Warn().Err(err).Msg("Failed to persist message, using in-memory fallback")
			return appendInMemory(messages, msg)
		}
		return updated
	}
	return appendInMemory(messages, msg)
}

func appendInMemory(messages []map[string]any, msg *model.ConversationMessage) []map[string]any {
	var content any
	if err := json.Unmarshal([]byte(msg.ContentJSON), &content); err != nil {
		content = msg.ContentJSON
	}
	return append(messages, map[string]any{
		"role":    msg.Role,
		"content": content,
	})
}

func (o *Orchestrator) executeTool(ctx context.Context, request *model.AgentRequest, tc tool.Call) tool.Result {
	sanitizeToolInputs(tc.Input)

	if o.guardedRegistry != nil {
		executor := func(execCtx context.Context, op *spi.OperationContext, toolName string, input map[string]any) (string, error) {
			if o.toolRegistry == nil {
				return "", fmt.Errorf("no tool registry configured")
			}
			result := o.toolRegistry.Execute(execCtx, op, tool.Call{
				ID:    tc.ID,
				Name:  toolName,
				Input: input,
			})
			if result.IsError {
				return result.Content, fmt.Errorf("tool error: %s", result.Content)
			}
			return result.Content, nil
		}

		result, _, err := o.guardedRegistry.CheckAndExecute(
			ctx, request.TicketKey, request.CommentAuthor, tc.Name, tc.Input,
			executor, &request.Context,
		)
		if err != nil {
			return tool.Error(tc.ID, err.Error())
		}
		return tool.Success(tc.ID, result)
	}

	if o.toolRegistry != nil {
		return o.toolRegistry.Execute(ctx, &request.Context, tc)
	}

	return tool.Error(tc.ID, fmt.Sprintf("no tool registry available to execute '%s'", tc.Name))
}

func extractToolCalls(contentBlocks json.RawMessage) ([]tool.Call, error) {
	var blocks []map[string]any
	if err := json.Unmarshal(contentBlocks, &blocks); err != nil {
		return nil, fmt.Errorf("unmarshal content blocks: %w", err)
	}

	calls := make([]tool.Call, 0, len(blocks))
	for _, block := range blocks {
		blockType, ok := block["type"].(string)
		if !ok || blockType != "tool_use" {
			continue
		}

		id, idOK := block["id"].(string)
		name, nameOK := block["name"].(string)
		if !idOK || !nameOK {
			continue
		}

		var input map[string]any
		if rawInput, ok := block["input"]; ok {
			if m, ok := rawInput.(map[string]any); ok {
				input = m
			}
		}

		calls = append(calls, tool.Call{
			ID:    id,
			Name:  name,
			Input: input,
		})
	}
	return calls, nil
}

func sanitizeToolInputs(input map[string]any) {
	for k, v := range input {
		if s, ok := v.(string); ok {
			input[k] = strings.TrimSpace(s)
		}
	}
}

func EstimateTokens(text string) int {
	n := len(text) / 4
	if n < 1 {
		return 1
	}
	return n
}

func createUserMessage(ticketKey, tenantID, body, author string) *model.ConversationMessage {
	contentJSON, err := json.Marshal([]map[string]any{
		{"type": "text", "text": body},
	})
	if err != nil {
		contentJSON = []byte("[]")
	}
	now := time.Now()
	return &model.ConversationMessage{
		PK:          model.CreateConversationPK(tenantID, ticketKey),
		SK:          model.CreateConversationSK(now, 0),
		Role:        "user",
		Author:      author,
		ContentJSON: string(contentJSON),
		Timestamp:   now,
		TokenCount:  EstimateTokens(body),
		TTL:         model.DefaultTTL(),
	}
}

func createAssistantMessage(ticketKey, tenantID string, contentBlocks json.RawMessage, tokenCount int) *model.ConversationMessage {
	now := time.Now()
	return &model.ConversationMessage{
		PK:          model.CreateConversationPK(tenantID, ticketKey),
		SK:          model.CreateConversationSK(now, 0),
		Role:        "assistant",
		Author:      "ai",
		ContentJSON: string(contentBlocks),
		Timestamp:   now,
		TokenCount:  tokenCount,
		TTL:         model.DefaultTTL(),
	}
}

func createToolResultMessage(ticketKey, tenantID string, toolResults []map[string]any) *model.ConversationMessage {
	contentJSON, err := json.Marshal(toolResults)
	if err != nil {
		contentJSON = []byte("[]")
	}
	now := time.Now()
	tokenEstimate := EstimateTokens(string(contentJSON))
	return &model.ConversationMessage{
		PK:          model.CreateConversationPK(tenantID, ticketKey),
		SK:          model.CreateConversationSK(now, 0),
		Role:        "user",
		Author:      "tool",
		ContentJSON: string(contentJSON),
		Timestamp:   now,
		TokenCount:  tokenEstimate,
		TTL:         model.DefaultTTL(),
	}
}
