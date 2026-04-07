package notification

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// SlackNotifier implements spi.ApprovalNotifier by posting messages to a Slack webhook.
type SlackNotifier struct {
	httpClient *http.Client
	webhookURL string
	channel    string
}

// NewSlackNotifier creates a new SlackNotifier with the given webhook URL and channel.
func NewSlackNotifier(webhookURL, channel string) *SlackNotifier {
	return &SlackNotifier{
		httpClient: &http.Client{},
		webhookURL: webhookURL,
		channel:    channel,
	}
}

// NotifyPending sends a Slack notification for a pending high-risk action approval.
func (s *SlackNotifier) NotifyPending(ctx context.Context, approval *spi.PendingApprovalContext) error {
	payload, err := s.buildPayload(approval)
	if err != nil {
		return fmt.Errorf("failed to build Slack payload: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.webhookURL, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("failed to create Slack request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to send Slack notification: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("Slack webhook returned status %d", resp.StatusCode)
	}

	log.Info().
		Str("ticketKey", approval.TicketKey).
		Str("toolName", approval.ToolName).
		Msg("sent Slack approval notification")

	return nil
}

type slackPayload struct {
	Text    string `json:"text"`
	Channel string `json:"channel"`
}

func (s *SlackNotifier) buildPayload(approval *spi.PendingApprovalContext) ([]byte, error) {
	text := fmt.Sprintf(
		"\U0001F6A8 *[AI Agent] HIGH RISK ACTION pending for ticket %s*\n\u2022 Action: %s\n\u2022 Reason: %s",
		approval.TicketKey,
		approval.ActionDescription,
		approval.TriggerReason,
	)

	payload := slackPayload{
		Text:    text,
		Channel: s.channel,
	}

	return json.Marshal(payload)
}
