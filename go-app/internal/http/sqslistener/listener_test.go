package sqslistener

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
	"github.com/AirdropToTheMoon/ai-driven/internal/http/handler"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

type mockSQSReceiver struct {
	mu             sync.Mutex
	messages       []sqstypes.Message
	deletedHandles []string
	receiveErr     error
	deleteErr      error
}

func (m *mockSQSReceiver) ReceiveMessage(_ context.Context, _ *sqs.ReceiveMessageInput, _ ...func(*sqs.Options)) (*sqs.ReceiveMessageOutput, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.receiveErr != nil {
		return nil, m.receiveErr
	}

	if len(m.messages) == 0 {
		return &sqs.ReceiveMessageOutput{Messages: []sqstypes.Message{}}, nil
	}

	msg := m.messages[0]
	m.messages = m.messages[1:]
	return &sqs.ReceiveMessageOutput{Messages: []sqstypes.Message{msg}}, nil
}

func (m *mockSQSReceiver) DeleteMessage(_ context.Context, params *sqs.DeleteMessageInput, _ ...func(*sqs.Options)) (*sqs.DeleteMessageOutput, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.deleteErr != nil {
		return nil, m.deleteErr
	}

	m.deletedHandles = append(m.deletedHandles, aws.ToString(params.ReceiptHandle))
	return &sqs.DeleteMessageOutput{}, nil
}

type mockProcessor struct {
	mu       sync.Mutex
	requests []*model.AgentRequest
	response *model.AgentResponse
	err      error
}

func (m *mockProcessor) Process(_ context.Context, request *model.AgentRequest, _ model.CommentIntent) (*model.AgentResponse, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.requests = append(m.requests, request)
	return m.response, m.err
}

type mockIssueTracker struct {
	mu       sync.Mutex
	comments []string
	err      error
}

func (m *mockIssueTracker) Name() string { return "mock" }
func (m *mockIssueTracker) GetTicketDetails(_ context.Context, _ *spi.OperationContext, _ string) (map[string]any, error) {
	return nil, nil
}
func (m *mockIssueTracker) PostComment(_ context.Context, _ *spi.OperationContext, _ string, comment string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.comments = append(m.comments, comment)
	return m.err
}
func (m *mockIssueTracker) UpdateLabels(_ context.Context, _ *spi.OperationContext, _ string, _, _ []string) error {
	return nil
}
func (m *mockIssueTracker) UpdateStatus(_ context.Context, _ *spi.OperationContext, _, _ string) error {
	return nil
}

func TestProcessSQSMessage_Success(t *testing.T) {
	task := handler.AgentTask{
		TicketKey:     "PROJ-123",
		Platform:      "jira",
		CommentBody:   "@ai review this",
		CommentAuthor: "John",
		TenantID:      "PROJ",
		Intent:        "AI_COMMAND",
		CorrelationID: "corr-123",
	}
	taskJSON, _ := json.Marshal(task)

	processor := &mockProcessor{
		response: &model.AgentResponse{
			Text:       "Here is my analysis...",
			TokenCount: 500,
			TurnCount:  2,
		},
	}
	tracker := &mockIssueTracker{}
	sqsMock := &mockSQSReceiver{}

	listener := NewListener(sqsMock, "https://queue-url", processor, tracker)
	listener.ProcessSQSMessage(t.Context(), string(taskJSON), "receipt-handle-1")

	require.Len(t, processor.requests, 1)
	assert.Equal(t, "PROJ-123", processor.requests[0].TicketKey)
	assert.Equal(t, "jira", processor.requests[0].Platform)

	require.Len(t, tracker.comments, 1)
	assert.Equal(t, "Here is my analysis...", tracker.comments[0])

	require.Len(t, sqsMock.deletedHandles, 1)
	assert.Equal(t, "receipt-handle-1", sqsMock.deletedHandles[0])
}

func TestProcessSQSMessage_ProcessorError(t *testing.T) {
	task := handler.AgentTask{
		TicketKey:     "PROJ-456",
		Platform:      "jira",
		CommentBody:   "@ai do something",
		CommentAuthor: "Jane",
		TenantID:      "PROJ",
		Intent:        "AI_COMMAND",
		CorrelationID: "corr-456",
	}
	taskJSON, _ := json.Marshal(task)

	processor := &mockProcessor{
		err: fmt.Errorf("AI processing failed"),
	}
	tracker := &mockIssueTracker{}
	sqsMock := &mockSQSReceiver{}

	listener := NewListener(sqsMock, "https://queue-url", processor, tracker)
	listener.ProcessSQSMessage(t.Context(), string(taskJSON), "receipt-handle-2")

	require.Len(t, tracker.comments, 1)
	assert.Contains(t, tracker.comments[0], "error occurred")

	assert.Empty(t, sqsMock.deletedHandles)
}

func TestProcessSQSMessage_InvalidJSON(t *testing.T) {
	sqsMock := &mockSQSReceiver{}
	processor := &mockProcessor{
		response: &model.AgentResponse{Text: "ok"},
	}
	tracker := &mockIssueTracker{}

	listener := NewListener(sqsMock, "https://queue-url", processor, tracker)
	listener.ProcessSQSMessage(t.Context(), "invalid-json{{{", "receipt-handle-3")

	assert.Empty(t, processor.requests)
	assert.Empty(t, sqsMock.deletedHandles)
}

func TestListener_StartStop(t *testing.T) {
	sqsMock := &mockSQSReceiver{}
	processor := &mockProcessor{
		response: &model.AgentResponse{Text: "ok"},
	}

	listener := NewListener(sqsMock, "https://queue-url", processor, nil)

	ctx, cancel := context.WithCancel(t.Context())
	listener.Start(ctx)

	time.Sleep(50 * time.Millisecond)
	cancel()

	done := make(chan struct{})
	go func() {
		listener.wg.Wait()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("listener did not stop within timeout")
	}
}

func TestListener_StopSignal(t *testing.T) {
	sqsMock := &mockSQSReceiver{}
	processor := &mockProcessor{
		response: &model.AgentResponse{Text: "ok"},
	}

	listener := NewListener(sqsMock, "https://queue-url", processor, nil)

	listener.Start(t.Context())
	time.Sleep(50 * time.Millisecond)

	done := make(chan struct{})
	go func() {
		listener.Stop()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("listener did not stop within timeout")
	}
}
