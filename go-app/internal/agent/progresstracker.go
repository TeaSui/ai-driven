package agent

import (
	"context"
	"fmt"

	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// JiraProgressTracker implements ProgressTracker by posting comments to an
// issue tracker (Jira, GitHub Issues, etc.) via the IssueTrackerProvider SPI.
// Because there is no EditComment API, each update appends a new comment.
//
// All errors are logged but never propagated — progress tracking must not
// interfere with the agent's core ReAct loop.
type JiraProgressTracker struct {
	issueTracker spi.IssueTrackerProvider
	opCtx        *spi.OperationContext
}

// NewJiraProgressTracker creates a progress tracker that uses the given
// IssueTrackerProvider and default OperationContext.
func NewJiraProgressTracker(issueTracker spi.IssueTrackerProvider, opCtx *spi.OperationContext) *JiraProgressTracker {
	return &JiraProgressTracker{
		issueTracker: issueTracker,
		opCtx:        opCtx,
	}
}

// UpdateProgress posts a processing-in-progress comment to the ticket
// identified by commentID (which here is the ticket key used as a routing key).
func (p *JiraProgressTracker) UpdateProgress(commentID string) {
	comment := "Processing... \u23f3"
	p.postComment(commentID, comment)
}

// Complete posts the final response as a comment.
func (p *JiraProgressTracker) Complete(commentID, finalResponse string) {
	p.postComment(commentID, finalResponse)
}

// Fail posts an error summary as a comment.
func (p *JiraProgressTracker) Fail(commentID, errorMessage string) {
	comment := fmt.Sprintf("An error occurred while processing your request: %s", errorMessage)
	p.postComment(commentID, comment)
}

// postComment is the shared helper that calls the SPI and swallows errors.
func (p *JiraProgressTracker) postComment(ticketKey, comment string) {
	ctx := context.Background()
	if err := p.issueTracker.PostComment(ctx, p.opCtx, ticketKey, comment); err != nil {
		log.Error().
			Err(err).
			Str("ticketKey", ticketKey).
			Str("provider", p.issueTracker.Name()).
			Msg("progress tracker failed to post comment")
	}
}
