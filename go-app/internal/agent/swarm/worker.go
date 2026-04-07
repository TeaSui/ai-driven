package swarm

import (
	"context"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

type WorkerAgent interface {
	Process(ctx context.Context, request *model.AgentRequest) (*model.AgentResponse, error)
}
