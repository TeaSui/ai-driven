package model

import "github.com/AirdropToTheMoon/ai-driven/internal/spi"

// AgentRequest is the input to the agent orchestrator.
type AgentRequest struct {
	TicketKey     string               `json:"ticketKey"`
	Platform      string               `json:"platform"`
	CommentBody   string               `json:"commentBody"`
	CommentAuthor string               `json:"commentAuthor"`
	TicketInfo    map[string]any       `json:"ticketInfo,omitempty"`
	AckCommentID  string               `json:"ackCommentId,omitempty"`
	Context       spi.OperationContext `json:"context"`
	PRContext     map[string]string    `json:"prContext,omitempty"`
}
