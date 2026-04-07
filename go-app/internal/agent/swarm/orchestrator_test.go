package swarm

import (
	"context"
	"fmt"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent"
	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

type mockAiClient struct {
	chatResponse string
	chatErr      error
}

func (m *mockAiClient) ChatWithTools(_ context.Context, _ string, _ []map[string]any, _ []map[string]any) (*agent.ToolUseResponse, error) {
	return nil, nil
}

func (m *mockAiClient) Chat(_ context.Context, _ string, _ string) (string, error) {
	return m.chatResponse, m.chatErr
}

func (m *mockAiClient) Model() string { return "test-model" }

func (m *mockAiClient) WithModel(_ string) agent.AiClient { return m }

func (m *mockAiClient) WithMaxTokens(_ int) agent.AiClient { return m }

func (m *mockAiClient) WithTemperature(_ float64) agent.AiClient { return m }

type mockWorker struct {
	responses []*model.AgentResponse
	errors    []error
	callCount int
}

func newMockWorker(responses ...*model.AgentResponse) *mockWorker {
	errs := make([]error, len(responses))
	return &mockWorker{responses: responses, errors: errs}
}

func (w *mockWorker) Process(_ context.Context, _ *model.AgentRequest) (*model.AgentResponse, error) {
	idx := w.callCount
	if idx >= len(w.responses) {
		idx = len(w.responses) - 1
	}
	w.callCount++
	return w.responses[idx], w.errors[idx]
}

func baseRequest() *model.AgentRequest {
	return &model.AgentRequest{
		TicketKey:     "PROJ-1",
		Platform:      "jira",
		CommentBody:   "implement caching",
		CommentAuthor: "alice",
	}
}

func TestProcess_QuestionIntent_RoutesToResearcher(t *testing.T) {
	researcher := newMockWorker(&model.AgentResponse{Text: "research result", ToolsUsed: []string{"search"}})
	coder := newMockWorker(&model.AgentResponse{Text: "code result"})

	o := NewOrchestrator(&mockAiClient{}, coder, researcher, newMockWorker(), newMockWorker())

	resp, err := o.Process(t.Context(), baseRequest(), model.IntentQuestion)

	require.NoError(t, err)
	assert.Equal(t, "research result", resp.Text)
	assert.Equal(t, 1, researcher.callCount)
	assert.Equal(t, 0, coder.callCount)
}

func TestProcess_ResearchClassification_RoutesToResearcher(t *testing.T) {
	researcher := newMockWorker(&model.AgentResponse{Text: "research answer"})
	client := &mockAiClient{chatResponse: "RESEARCH"}

	o := NewOrchestrator(client, newMockWorker(), researcher, newMockWorker(), newMockWorker())

	resp, err := o.Process(t.Context(), baseRequest(), model.IntentAICommand)

	require.NoError(t, err)
	assert.Equal(t, "research answer", resp.Text)
	assert.Equal(t, 1, researcher.callCount)
}

func TestProcess_ImplementationApprovedAndPassed(t *testing.T) {
	coder := newMockWorker(&model.AgentResponse{Text: "code changes", ToolsUsed: []string{"edit_file"}, TokenCount: 100, TurnCount: 1})
	reviewer := newMockWorker(&model.AgentResponse{Text: "APPROVED - looks great", ToolsUsed: []string{"read_file"}, TokenCount: 50, TurnCount: 1})
	tester := newMockWorker(&model.AgentResponse{Text: "PASSED - all tests green", ToolsUsed: []string{"run_tests"}, TokenCount: 30, TurnCount: 1})
	client := &mockAiClient{chatResponse: "IMPLEMENTATION"}

	o := NewOrchestrator(client, coder, newMockWorker(), reviewer, tester)

	resp, err := o.Process(t.Context(), baseRequest(), model.IntentAICommand)

	require.NoError(t, err)
	assert.Contains(t, resp.Text, "code changes")
	assert.Contains(t, resp.Text, "APPROVED - looks great")
	assert.Contains(t, resp.Text, "PASSED - all tests green")
	assert.Equal(t, 180, resp.TokenCount)
	assert.Equal(t, 3, resp.TurnCount)
	assert.Contains(t, resp.ToolsUsed, "edit_file")
	assert.Contains(t, resp.ToolsUsed, "read_file")
	assert.Contains(t, resp.ToolsUsed, "run_tests")
}

func TestProcess_ImplementationRejectedThenApprovedAndPassed(t *testing.T) {
	coder := newMockWorker(
		&model.AgentResponse{Text: "first attempt", ToolsUsed: []string{"edit"}, TokenCount: 100, TurnCount: 1},
		&model.AgentResponse{Text: "second attempt", ToolsUsed: []string{"edit"}, TokenCount: 100, TurnCount: 1},
	)
	reviewer := newMockWorker(
		&model.AgentResponse{Text: "REJECTED - missing error handling", ToolsUsed: []string{"read"}, TokenCount: 50, TurnCount: 1},
		&model.AgentResponse{Text: "APPROVED", ToolsUsed: []string{"read"}, TokenCount: 50, TurnCount: 1},
	)
	tester := newMockWorker(&model.AgentResponse{Text: "PASSED", ToolsUsed: []string{"test"}, TokenCount: 30, TurnCount: 1})
	client := &mockAiClient{chatResponse: "IMPLEMENTATION"}

	o := NewOrchestrator(client, coder, newMockWorker(), reviewer, tester)

	resp, err := o.Process(t.Context(), baseRequest(), model.IntentAICommand)

	require.NoError(t, err)
	assert.Contains(t, resp.Text, "second attempt")
	assert.Contains(t, resp.Text, "APPROVED")
	assert.Contains(t, resp.Text, "PASSED")
	assert.Equal(t, 2, coder.callCount)
	assert.Equal(t, 2, reviewer.callCount)
}

func TestProcess_MaxReviewLoops_ReturnsFinalCoderResponse(t *testing.T) {
	coder := newMockWorker(
		&model.AgentResponse{Text: "attempt 1", TokenCount: 100, TurnCount: 1},
		&model.AgentResponse{Text: "attempt 2", TokenCount: 100, TurnCount: 1},
		&model.AgentResponse{Text: "attempt 3", TokenCount: 100, TurnCount: 1},
	)
	reviewer := newMockWorker(
		&model.AgentResponse{Text: "REJECTED", TokenCount: 50, TurnCount: 1},
		&model.AgentResponse{Text: "REJECTED", TokenCount: 50, TurnCount: 1},
	)
	client := &mockAiClient{chatResponse: "IMPLEMENTATION"}

	o := NewOrchestrator(client, coder, newMockWorker(), reviewer, newMockWorker())

	resp, err := o.Process(t.Context(), baseRequest(), model.IntentAICommand)

	require.NoError(t, err)
	assert.Equal(t, "attempt 3", resp.Text)
	assert.Equal(t, 3, coder.callCount)
}

func TestProcess_ClassifyIntentError_DefaultsToImplementation(t *testing.T) {
	coder := newMockWorker(&model.AgentResponse{Text: "code", TokenCount: 100, TurnCount: 1})
	reviewer := newMockWorker(&model.AgentResponse{Text: "APPROVED", TokenCount: 50, TurnCount: 1})
	tester := newMockWorker(&model.AgentResponse{Text: "PASSED", TokenCount: 30, TurnCount: 1})
	client := &mockAiClient{chatErr: fmt.Errorf("network error")}

	o := NewOrchestrator(client, coder, newMockWorker(), reviewer, tester)

	resp, err := o.Process(t.Context(), baseRequest(), model.IntentAICommand)

	require.NoError(t, err)
	assert.Contains(t, resp.Text, "code")
}

func TestMergeTools_Deduplicates(t *testing.T) {
	result := mergeTools(
		&model.AgentResponse{ToolsUsed: []string{"a", "b"}},
		&model.AgentResponse{ToolsUsed: []string{"b", "c"}},
		&model.AgentResponse{ToolsUsed: []string{"a", "d"}},
	)

	assert.Equal(t, []string{"a", "b", "c", "d"}, result)
}
