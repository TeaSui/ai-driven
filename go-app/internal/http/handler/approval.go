package handler

import (
	"net/http"
	"strings"

	"github.com/labstack/echo/v4"
	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/guardrail"
	"github.com/AirdropToTheMoon/ai-driven/internal/security"
)

var rejectionKeywords = []string{
	"reject", "cancel", "don't do it", "abort", "stop", "no don't", "no, don't",
}

// ApprovalHandler processes approval/rejection requests for guarded tool actions.
type ApprovalHandler struct {
	approvalStore *guardrail.ApprovalStore
}

type approvalRequest struct {
	TicketKey   string `json:"ticketKey"`
	CommentBody string `json:"commentBody"`
	Platform    string `json:"platform"`
	TenantID    string `json:"tenantId"`
	UserID      string `json:"userId"`
}

// NewApprovalHandler creates a new ApprovalHandler.
func NewApprovalHandler(store *guardrail.ApprovalStore) *ApprovalHandler {
	return &ApprovalHandler{approvalStore: store}
}

// ProcessApproval handles an approval or rejection for a pending guarded action.
func (h *ApprovalHandler) ProcessApproval(c echo.Context) error {
	ctx := c.Request().Context()

	var req approvalRequest
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request"})
	}

	if err := security.ValidateTicketKey(req.TicketKey); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid ticket key"})
	}

	lowerBody := strings.ToLower(req.CommentBody)
	for _, keyword := range rejectionKeywords {
		if strings.Contains(lowerBody, keyword) {
			log.Ctx(ctx).Info().
				Str("ticketKey", req.TicketKey).
				Str("keyword", keyword).
				Msg("Approval rejected by user")
			return c.JSON(http.StatusOK, map[string]string{
				"status":    "rejected",
				"ticketKey": req.TicketKey,
			})
		}
	}

	pending, err := h.approvalStore.GetLatestPending(ctx, req.TicketKey)
	if err != nil {
		log.Ctx(ctx).Error().Err(err).Str("ticketKey", req.TicketKey).Msg("Failed to retrieve pending approval")
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to retrieve approval"})
	}

	if pending == nil {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "no pending approval found"})
	}

	if err := h.approvalStore.ConsumeApproval(ctx, req.TicketKey, pending.SK); err != nil {
		log.Ctx(ctx).Error().Err(err).Str("ticketKey", req.TicketKey).Msg("Failed to consume approval")
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": "failed to process approval"})
	}

	log.Ctx(ctx).Info().
		Str("ticketKey", req.TicketKey).
		Str("toolName", pending.ToolName).
		Msg("Approval consumed successfully")

	return c.JSON(http.StatusOK, map[string]string{
		"status":    "approved",
		"ticketKey": req.TicketKey,
		"toolName":  pending.ToolName,
	})
}
