package tool

import (
	"context"
	"errors"
	"testing"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// --- mock issue tracker ---

type mockIssueTracker struct {
	getTicketFn    func(ctx context.Context, op *spi.OperationContext, key string) (map[string]any, error)
	postCommentFn  func(ctx context.Context, op *spi.OperationContext, key, comment string) error
	updateStatusFn func(ctx context.Context, op *spi.OperationContext, key, status string) error
}

func (m *mockIssueTracker) Name() string { return "mock-tracker" }
func (m *mockIssueTracker) GetTicketDetails(ctx context.Context, op *spi.OperationContext, key string) (map[string]any, error) {
	if m.getTicketFn != nil {
		return m.getTicketFn(ctx, op, key)
	}
	return map[string]any{"key": key, "summary": "Test ticket", "status": "Open"}, nil
}
func (m *mockIssueTracker) PostComment(ctx context.Context, op *spi.OperationContext, key, comment string) error {
	if m.postCommentFn != nil {
		return m.postCommentFn(ctx, op, key, comment)
	}
	return nil
}
func (m *mockIssueTracker) UpdateLabels(context.Context, *spi.OperationContext, string, []string, []string) error {
	return nil
}
func (m *mockIssueTracker) UpdateStatus(ctx context.Context, op *spi.OperationContext, key, status string) error {
	if m.updateStatusFn != nil {
		return m.updateStatusFn(ctx, op, key, status)
	}
	return nil
}

func newTestITProvider() (*IssueTrackerToolProvider, *mockIssueTracker) {
	mock := &mockIssueTracker{}
	return NewIssueTrackerToolProvider(mock), mock
}

func TestIssueTrackerToolProvider_Namespace(t *testing.T) {
	p, _ := newTestITProvider()
	assert.Equal(t, "issue_tracker", p.Namespace())
}

func TestIssueTrackerToolProvider_Definitions(t *testing.T) {
	p, _ := newTestITProvider()
	defs := p.Definitions()
	assert.Len(t, defs, 3)

	names := make(map[string]bool)
	for _, d := range defs {
		names[d.Name] = true
		assert.NotEmpty(t, d.Description)
	}
	assert.True(t, names["issue_tracker_get_ticket"])
	assert.True(t, names["issue_tracker_add_comment"])
	assert.True(t, names["issue_tracker_update_status"])
}

func TestIssueTrackerToolProvider_GetTicket(t *testing.T) {
	p, _ := newTestITProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "issue_tracker_get_ticket",
		Input: map[string]any{"ticket_key": "PROJ-123"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "Test ticket")
}

func TestIssueTrackerToolProvider_GetTicket_Error(t *testing.T) {
	p, mock := newTestITProvider()
	mock.getTicketFn = func(context.Context, *spi.OperationContext, string) (map[string]any, error) {
		return nil, errors.New("ticket not found")
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "issue_tracker_get_ticket",
		Input: map[string]any{"ticket_key": "PROJ-999"},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "ticket not found")
}

func TestIssueTrackerToolProvider_GetTicket_MissingKey(t *testing.T) {
	p, _ := newTestITProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "issue_tracker_get_ticket",
		Input: map[string]any{},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "ticket_key is required")
}

func TestIssueTrackerToolProvider_AddComment(t *testing.T) {
	p, _ := newTestITProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "issue_tracker_add_comment",
		Input: map[string]any{"ticket_key": "PROJ-123", "comment": "Test comment"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "Comment added")
}

func TestIssueTrackerToolProvider_AddComment_Error(t *testing.T) {
	p, mock := newTestITProvider()
	mock.postCommentFn = func(context.Context, *spi.OperationContext, string, string) error {
		return errors.New("permission denied")
	}
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "issue_tracker_add_comment",
		Input: map[string]any{"ticket_key": "PROJ-123", "comment": "Test"},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "permission denied")
}

func TestIssueTrackerToolProvider_UpdateStatus(t *testing.T) {
	p, _ := newTestITProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "issue_tracker_update_status",
		Input: map[string]any{"ticket_key": "PROJ-123", "status": "In Progress"},
	})
	assert.False(t, out.IsError)
	assert.Contains(t, out.Content, "In Progress")
}

func TestIssueTrackerToolProvider_UnknownTool(t *testing.T) {
	p, _ := newTestITProvider()
	out := p.Execute(t.Context(), testOp(), CallInput{
		ID:    "t1",
		Name:  "issue_tracker_nonexistent",
		Input: map[string]any{},
	})
	assert.True(t, out.IsError)
	assert.Contains(t, out.Content, "unknown tool")
}

func TestIssueTrackerToolProvider_ImplementsProvider(t *testing.T) {
	p, _ := newTestITProvider()
	var _ Provider = p
	require.NotNil(t, p)
}
