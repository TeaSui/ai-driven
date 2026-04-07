package agent

import (
	"context"
	"fmt"
	"sync"
	"testing"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// --- mock IssueTrackerProvider ---

type mockIssueTracker struct {
	mu       sync.Mutex
	comments []postedComment
	postErr  error
}

type postedComment struct {
	ticketKey string
	comment   string
}

func (m *mockIssueTracker) Name() string { return "mock" }

func (m *mockIssueTracker) GetTicketDetails(_ context.Context, _ *spi.OperationContext, _ string) (map[string]any, error) {
	return nil, nil
}

func (m *mockIssueTracker) PostComment(_ context.Context, _ *spi.OperationContext, ticketKey, comment string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.comments = append(m.comments, postedComment{ticketKey: ticketKey, comment: comment})
	return m.postErr
}

func (m *mockIssueTracker) UpdateLabels(_ context.Context, _ *spi.OperationContext, _ string, _, _ []string) error {
	return nil
}

func (m *mockIssueTracker) UpdateStatus(_ context.Context, _ *spi.OperationContext, _, _ string) error {
	return nil
}

func (m *mockIssueTracker) getComments() []postedComment {
	m.mu.Lock()
	defer m.mu.Unlock()
	dst := make([]postedComment, len(m.comments))
	copy(dst, m.comments)
	return dst
}

// --- tests ---

func newTestTracker(tracker *mockIssueTracker) *JiraProgressTracker {
	opCtx := &spi.OperationContext{TenantID: "tenant-1"}
	return NewJiraProgressTracker(tracker, opCtx)
}

func TestUpdateProgress_PostsProcessingComment(t *testing.T) {
	mock := &mockIssueTracker{}
	pt := newTestTracker(mock)

	pt.UpdateProgress("PROJ-123")

	comments := mock.getComments()
	if len(comments) != 1 {
		t.Fatalf("expected 1 comment, got %d", len(comments))
	}
	if comments[0].ticketKey != "PROJ-123" {
		t.Errorf("expected ticketKey PROJ-123, got %s", comments[0].ticketKey)
	}
	if comments[0].comment != "Processing... \u23f3" {
		t.Errorf("unexpected comment text: %s", comments[0].comment)
	}
}

func TestComplete_PostsFinalResponse(t *testing.T) {
	mock := &mockIssueTracker{}
	pt := newTestTracker(mock)

	pt.Complete("PROJ-456", "Here is the answer.")

	comments := mock.getComments()
	if len(comments) != 1 {
		t.Fatalf("expected 1 comment, got %d", len(comments))
	}
	if comments[0].ticketKey != "PROJ-456" {
		t.Errorf("expected ticketKey PROJ-456, got %s", comments[0].ticketKey)
	}
	if comments[0].comment != "Here is the answer." {
		t.Errorf("unexpected comment text: %s", comments[0].comment)
	}
}

func TestFail_PostsErrorComment(t *testing.T) {
	mock := &mockIssueTracker{}
	pt := newTestTracker(mock)

	pt.Fail("PROJ-789", "timeout exceeded")

	comments := mock.getComments()
	if len(comments) != 1 {
		t.Fatalf("expected 1 comment, got %d", len(comments))
	}
	if comments[0].ticketKey != "PROJ-789" {
		t.Errorf("expected ticketKey PROJ-789, got %s", comments[0].ticketKey)
	}
	expected := "An error occurred while processing your request: timeout exceeded"
	if comments[0].comment != expected {
		t.Errorf("expected %q, got %q", expected, comments[0].comment)
	}
}

func TestUpdateProgress_DoesNotPanic_OnPostError(t *testing.T) {
	mock := &mockIssueTracker{postErr: fmt.Errorf("network failure")}
	pt := newTestTracker(mock)

	// Must not panic or propagate error
	pt.UpdateProgress("PROJ-ERR")
	pt.Complete("PROJ-ERR", "done")
	pt.Fail("PROJ-ERR", "oops")

	// All three calls should have been attempted
	comments := mock.getComments()
	if len(comments) != 3 {
		t.Fatalf("expected 3 comment attempts, got %d", len(comments))
	}
}

func TestJiraProgressTracker_ImplementsInterface(t *testing.T) {
	// Compile-time check that JiraProgressTracker satisfies ProgressTracker.
	var _ ProgressTracker = (*JiraProgressTracker)(nil)
}
