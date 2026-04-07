package swarm

import (
	"context"
	"fmt"
	"strings"

	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent"
	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

const maxReviewLoops = 2

type Orchestrator struct {
	routingClient   agent.AiClient
	coderAgent      WorkerAgent
	researcherAgent WorkerAgent
	reviewerAgent   WorkerAgent
	testerAgent     WorkerAgent
}

func NewOrchestrator(routingClient agent.AiClient, coder, researcher, reviewer, tester WorkerAgent) *Orchestrator {
	return &Orchestrator{
		routingClient:   routingClient,
		coderAgent:      coder,
		researcherAgent: researcher,
		reviewerAgent:   reviewer,
		testerAgent:     tester,
	}
}

func (o *Orchestrator) Process(ctx context.Context, request *model.AgentRequest, intent model.CommentIntent) (*model.AgentResponse, error) {
	logger := log.Ctx(ctx).With().Str("ticket", request.TicketKey).Str("intent", string(intent)).Logger()
	logger.Info().Msg("SwarmOrchestrator processing request")

	if intent == model.IntentQuestion {
		return o.researcherAgent.Process(ctx, request)
	}

	swarmIntent := o.classifyIntent(ctx, request)
	logger.Info().Str("swarmIntent", swarmIntent).Msg("Classified swarm intent")

	if swarmIntent == "RESEARCH" {
		return o.researcherAgent.Process(ctx, request)
	}

	return o.handleImplementationWithFullLoop(ctx, request, &logger)
}

func (o *Orchestrator) handleImplementationWithFullLoop(ctx context.Context, request *model.AgentRequest, logger *zerolog.Logger) (*model.AgentResponse, error) {
	coderResponse, err := o.coderAgent.Process(ctx, request)
	if err != nil {
		return nil, fmt.Errorf("coder agent failed: %w", err)
	}

	for i := range maxReviewLoops {
		logger.Info().Int("turn", i+1).Msg("SwarmOrchestrator starting sequence turn")

		reviewResponse, err := o.reviewerAgent.Process(ctx, request)
		if err != nil {
			return nil, fmt.Errorf("reviewer agent failed: %w", err)
		}

		if !strings.HasPrefix(strings.ToUpper(strings.TrimSpace(reviewResponse.Text)), "APPROVED") {
			logger.Info().Msg("Review REJECTED, looping back to Coder")
			coderResponse, err = o.coderAgent.Process(ctx, request)
			if err != nil {
				return nil, fmt.Errorf("coder agent failed: %w", err)
			}
			continue
		}

		logger.Info().Msg("Review APPROVED, proceeding to Test")

		testResponse, err := o.testerAgent.Process(ctx, request)
		if err != nil {
			return nil, fmt.Errorf("tester agent failed: %w", err)
		}

		if strings.HasPrefix(strings.ToUpper(strings.TrimSpace(testResponse.Text)), "PASSED") {
			logger.Info().Msg("Testing PASSED")
			return &model.AgentResponse{
				Text: coderResponse.Text + "\n\n### Peer Review\n" + reviewResponse.Text +
					"\n\n### Automated Testing\n" + testResponse.Text,
				ToolsUsed:  mergeTools(coderResponse, reviewResponse, testResponse),
				TokenCount: coderResponse.TokenCount + reviewResponse.TokenCount + testResponse.TokenCount,
				TurnCount:  coderResponse.TurnCount + reviewResponse.TurnCount + testResponse.TurnCount,
			}, nil
		}

		logger.Info().Msg("Testing FAILED, looping back to Coder")
		coderResponse, err = o.coderAgent.Process(ctx, request)
		if err != nil {
			return nil, fmt.Errorf("coder agent failed: %w", err)
		}
	}

	return coderResponse, nil
}

func mergeTools(responses ...*model.AgentResponse) []string {
	seen := make(map[string]struct{})
	var result []string
	for _, r := range responses {
		for _, tool := range r.ToolsUsed {
			if _, exists := seen[tool]; !exists {
				seen[tool] = struct{}{}
				result = append(result, tool)
			}
		}
	}
	return result
}

func (o *Orchestrator) classifyIntent(ctx context.Context, request *model.AgentRequest) string {
	prompt := fmt.Sprintf(`You are a task router for a multi-agent system.
Your goal is to classify if the following user request is a RESEARCH/Q&A task or an IMPLEMENTATION/CODING task.

- RESEARCH: Asking questions about how things work, finding where code is located, explaining logic, or general exploration.
- IMPLEMENTATION: Requests to write code, fix bugs, create PRs, or make any changes to files.

Request context:
Ticket: %s
Author: %s
Body: %s

Respond with ONLY 'RESEARCH' or 'IMPLEMENTATION'.`, request.TicketKey, request.CommentAuthor, request.CommentBody)

	result, err := o.routingClient.Chat(ctx, prompt, "")
	if err != nil {
		log.Ctx(ctx).Error().Err(err).Msg("Failed to classify intent, defaulting to IMPLEMENTATION")
		return "IMPLEMENTATION"
	}

	upper := strings.ToUpper(strings.TrimSpace(result))
	if strings.Contains(upper, "RESEARCH") {
		return "RESEARCH"
	}
	return "IMPLEMENTATION"
}
