package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// --- Mocks ---

type mockAiClient struct {
	responses []*ToolUseResponse
	errors    []error
	callIndex int
	model     string
}

func (m *mockAiClient) ChatWithTools(_ context.Context, _ string, _ []map[string]any, _ []map[string]any) (*ToolUseResponse, error) {
	if m.callIndex >= len(m.responses) {
		return nil, fmt.Errorf("no more mock responses (call %d)", m.callIndex)
	}
	resp := m.responses[m.callIndex]
	var err error
	if m.callIndex < len(m.errors) {
		err = m.errors[m.callIndex]
	}
	m.callIndex++
	return resp, err
}

func (m *mockAiClient) Chat(_ context.Context, _ string, _ string) (string, error) {
	return "mock chat", nil
}

func (m *mockAiClient) Model() string {
	if m.model != "" {
		return m.model
	}
	return "mock-model"
}

func (m *mockAiClient) WithModel(_ string) AiClient        { return m }
func (m *mockAiClient) WithMaxTokens(_ int) AiClient       { return m }
func (m *mockAiClient) WithTemperature(_ float64) AiClient { return m }

type mockCostTracker struct {
	hasBudget    bool
	tokensAdded  int
	addCallCount int
}

func (m *mockCostTracker) HasRemainingBudget(_ string) bool {
	return m.hasBudget
}

func (m *mockCostTracker) AddTokens(_ string, tokens int) {
	m.tokensAdded += tokens
	m.addCallCount++
}

// --- Helpers ---

func textResponse(text string) *ToolUseResponse {
	blocks, _ := json.Marshal([]map[string]any{
		{"type": "text", "text": text},
	})
	return &ToolUseResponse{
		ContentBlocks: blocks,
		StopReason:    "end_turn",
		InputTokens:   10,
		OutputTokens:  20,
	}
}

func toolUseResponse(toolID, toolName string, input map[string]any) *ToolUseResponse {
	blocks, _ := json.Marshal([]map[string]any{
		{"type": "tool_use", "id": toolID, "name": toolName, "input": input},
	})
	return &ToolUseResponse{
		ContentBlocks: blocks,
		StopReason:    "tool_use",
		InputTokens:   15,
		OutputTokens:  25,
	}
}

func testRequest() *model.AgentRequest {
	return &model.AgentRequest{
		TicketKey:     "PROJ-123",
		Platform:      "jira",
		CommentBody:   "Hello AI",
		CommentAuthor: "user1",
		Context:       spi.NewOperationContext("tenant1"),
	}
}

// --- Tests ---

func TestOrchestrator_Process_SimpleTextResponse(t *testing.T) {
	client := &mockAiClient{
		responses: []*ToolUseResponse{
			textResponse("Here is my answer"),
		},
	}

	orch := NewOrchestrator(client)
	resp, err := orch.Process(t.Context(), testRequest(), model.IntentAICommand)

	require.NoError(t, err)
	assert.Equal(t, "Here is my answer", resp.Text)
	assert.Empty(t, resp.ToolsUsed)
	assert.Equal(t, 1, resp.TurnCount)
	assert.Equal(t, 30, resp.TokenCount)
}

func TestOrchestrator_Process_WithToolUse(t *testing.T) {
	client := &mockAiClient{
		responses: []*ToolUseResponse{
			toolUseResponse("call-1", "source_control_get_file", map[string]any{"path": "main.go"}),
			textResponse("I found the file contents"),
		},
	}

	orch := NewOrchestrator(client)
	resp, err := orch.Process(t.Context(), testRequest(), model.IntentAICommand)

	require.NoError(t, err)
	assert.Equal(t, "I found the file contents", resp.Text)
	assert.Equal(t, []string{"source_control_get_file"}, resp.ToolsUsed)
	assert.Equal(t, 2, resp.TurnCount)
	assert.Equal(t, 70, resp.TokenCount) // 40 + 30
}

func TestOrchestrator_Process_MaxTurns(t *testing.T) {
	// All responses are tool_use, so we hit max turns
	var responses []*ToolUseResponse
	for i := 0; i < 3; i++ {
		responses = append(responses, toolUseResponse(
			fmt.Sprintf("call-%d", i), "some_tool", map[string]any{"key": "val"},
		))
	}

	client := &mockAiClient{responses: responses}
	orch := NewOrchestrator(client, WithMaxTurns(3))

	resp, err := orch.Process(t.Context(), testRequest(), model.IntentAICommand)

	require.NoError(t, err)
	assert.Contains(t, resp.Text, "maximum number of turns (3)")
	assert.Len(t, resp.ToolsUsed, 3)
	assert.Equal(t, 3, resp.TurnCount)
}

func TestOrchestrator_Process_BudgetExhausted(t *testing.T) {
	client := &mockAiClient{
		responses: []*ToolUseResponse{textResponse("should not reach")},
	}
	tracker := &mockCostTracker{hasBudget: false}

	orch := NewOrchestrator(client, WithCostTracker(tracker))
	resp, err := orch.Process(t.Context(), testRequest(), model.IntentAICommand)

	require.NoError(t, err)
	assert.Contains(t, resp.Text, "Budget exhausted")
	assert.Equal(t, 0, client.callIndex) // AI was never called
}

func TestOrchestrator_Process_NoWindowManager(t *testing.T) {
	client := &mockAiClient{
		responses: []*ToolUseResponse{textResponse("in-memory response")},
	}

	orch := NewOrchestrator(client) // no window manager
	resp, err := orch.Process(t.Context(), testRequest(), model.IntentAICommand)

	require.NoError(t, err)
	assert.Equal(t, "in-memory response", resp.Text)
	assert.Equal(t, 1, resp.TurnCount)
}

func TestOrchestrator_Process_CostTrackerAddTokens(t *testing.T) {
	client := &mockAiClient{
		responses: []*ToolUseResponse{textResponse("done")},
	}
	tracker := &mockCostTracker{hasBudget: true}

	orch := NewOrchestrator(client, WithCostTracker(tracker))
	_, err := orch.Process(t.Context(), testRequest(), model.IntentAICommand)

	require.NoError(t, err)
	assert.Equal(t, 30, tracker.tokensAdded)
	assert.Equal(t, 1, tracker.addCallCount)
}

func TestEstimateTokens(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		expected int
	}{
		{"empty string", "", 1},
		{"short string", "hi", 1},
		{"four chars", "abcd", 1},
		{"eight chars", "abcdefgh", 2},
		{"long string", "abcdefghijklmnop", 4},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			assert.Equal(t, tt.expected, EstimateTokens(tt.input))
		})
	}
}

func TestExtractToolCalls(t *testing.T) {
	blocks, _ := json.Marshal([]map[string]any{
		{"type": "text", "text": "Let me look that up"},
		{"type": "tool_use", "id": "tc-1", "name": "search", "input": map[string]any{"q": "test"}},
		{"type": "tool_use", "id": "tc-2", "name": "read_file", "input": map[string]any{"path": "/a.go"}},
	})

	calls, err := extractToolCalls(blocks)
	require.NoError(t, err)
	assert.Len(t, calls, 2)
	assert.Equal(t, "tc-1", calls[0].ID)
	assert.Equal(t, "search", calls[0].Name)
	assert.Equal(t, "test", calls[0].Input["q"])
	assert.Equal(t, "tc-2", calls[1].ID)
	assert.Equal(t, "read_file", calls[1].Name)
}

func TestSanitizeToolInputs(t *testing.T) {
	input := map[string]any{
		"name":  "  hello  ",
		"count": 42,
		"path":  "\t/some/path\n",
	}
	sanitizeToolInputs(input)

	assert.Equal(t, "hello", input["name"])
	assert.Equal(t, 42, input["count"]) // non-string unchanged
	assert.Equal(t, "/some/path", input["path"])
}
