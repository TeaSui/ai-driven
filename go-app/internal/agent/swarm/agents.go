package swarm

import (
	"context"

	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

type AgentProcessor interface {
	Process(ctx context.Context, request *model.AgentRequest, intent model.CommentIntent) (*model.AgentResponse, error)
}

type CoderAgent struct {
	processor AgentProcessor
}

func NewCoderAgent(processor AgentProcessor) *CoderAgent {
	return &CoderAgent{processor: processor}
}

func (a *CoderAgent) Process(ctx context.Context, request *model.AgentRequest) (*model.AgentResponse, error) {
	log.Ctx(ctx).Info().Str("ticket", request.TicketKey).Msg("CoderAgent processing request")
	return a.processor.Process(ctx, request, model.IntentAICommand)
}

type ResearcherAgent struct {
	processor AgentProcessor
}

func NewResearcherAgent(processor AgentProcessor) *ResearcherAgent {
	return &ResearcherAgent{processor: processor}
}

func (a *ResearcherAgent) Process(ctx context.Context, request *model.AgentRequest) (*model.AgentResponse, error) {
	log.Ctx(ctx).Info().Str("ticket", request.TicketKey).Msg("ResearcherAgent processing request")
	return a.processor.Process(ctx, request, model.IntentAICommand)
}

type ReviewerAgent struct {
	processor AgentProcessor
}

func NewReviewerAgent(processor AgentProcessor) *ReviewerAgent {
	return &ReviewerAgent{processor: processor}
}

func (a *ReviewerAgent) Process(ctx context.Context, request *model.AgentRequest) (*model.AgentResponse, error) {
	log.Ctx(ctx).Info().Str("ticket", request.TicketKey).Msg("ReviewerAgent processing review")
	return a.processor.Process(ctx, request, model.IntentReview)
}

type TesterAgent struct {
	processor AgentProcessor
}

func NewTesterAgent(processor AgentProcessor) *TesterAgent {
	return &TesterAgent{processor: processor}
}

func (a *TesterAgent) Process(ctx context.Context, request *model.AgentRequest) (*model.AgentResponse, error) {
	log.Ctx(ctx).Info().Str("ticket", request.TicketKey).Msg("TesterAgent processing test request")
	return a.processor.Process(ctx, request, model.IntentTest)
}
