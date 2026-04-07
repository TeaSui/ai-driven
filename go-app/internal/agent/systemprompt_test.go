package agent

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

func TestAppendPersona(t *testing.T) {
	result := NewSystemPromptBuilder().AppendPersona().Build()

	assert.Contains(t, result, "expert AI development assistant")
	assert.Contains(t, result, "embedded in Jira and GitHub")
	assert.Contains(t, result, "direct dialogue partner")
}

func TestAppendContext_BasicFields(t *testing.T) {
	req := model.AgentRequest{
		TicketKey:     "PROJ-123",
		Platform:      "jira",
		CommentAuthor: "alice",
	}

	result := NewSystemPromptBuilder().AppendContext(&req).Build()

	assert.Contains(t, result, "- Ticket: PROJ-123")
	assert.Contains(t, result, "- Platform: jira")
	assert.Contains(t, result, "- Requested by: alice")
}

func TestAppendContext_WithTicketInfo(t *testing.T) {
	req := model.AgentRequest{
		TicketKey:     "PROJ-456",
		Platform:      "jira",
		CommentAuthor: "bob",
		TicketInfo: map[string]any{
			"summary":     "Fix login bug",
			"description": "Users cannot log in when using SSO.",
		},
	}

	result := NewSystemPromptBuilder().AppendContext(&req).Build()

	assert.Contains(t, result, "- Title: Fix login bug")
	assert.Contains(t, result, "- Description:\nUsers cannot log in when using SSO.")
}

func TestAppendContext_TruncatesLongDescription(t *testing.T) {
	longDesc := strings.Repeat("x", 3000)
	req := model.AgentRequest{
		TicketKey:     "PROJ-789",
		Platform:      "jira",
		CommentAuthor: "carol",
		TicketInfo: map[string]any{
			"summary":     "Long ticket",
			"description": longDesc,
		},
	}

	result := NewSystemPromptBuilder().AppendContext(&req).Build()

	assert.Contains(t, result, "- Description:")
	assert.True(t, strings.Contains(result, "\u2026"), "should contain ellipsis for truncated text")
	assert.LessOrEqual(t, len(result), 3000, "total prompt should be bounded")
}

func TestAppendContext_WithPRContext(t *testing.T) {
	req := model.AgentRequest{
		TicketKey:     "PROJ-100",
		Platform:      "github",
		CommentAuthor: "dave",
		PRContext: map[string]string{
			"filePath": "src/main.go",
			"commitId": "abc123",
			"diffHunk": "+ added line\n- removed line",
		},
	}

	result := NewSystemPromptBuilder().AppendContext(&req).Build()

	assert.Contains(t, result, "## GitHub PR Line Context")
	assert.Contains(t, result, "- File: `src/main.go`")
	assert.Contains(t, result, "- Commit: `abc123`")
	assert.Contains(t, result, "```diff\n+ added line\n- removed line\n```")
}

func TestAppendIntentGuidelines_AllIntents(t *testing.T) {
	tests := []struct {
		intent   model.CommentIntent
		contains string
	}{
		{model.IntentHumanFeedback, "Intent: Feedback on Your Previous Work"},
		{model.IntentQuestion, "Intent: Question"},
		{model.IntentApproval, "Intent: Approval / Rejection"},
		{model.IntentReview, "Intent: Peer Review"},
		{model.IntentTest, "Intent: Automated Testing"},
		{model.IntentAICommand, "## Guidelines"},
	}

	for _, tt := range tests {
		t.Run(string(tt.intent), func(t *testing.T) {
			result := NewSystemPromptBuilder().AppendIntentGuidelines(tt.intent).Build()
			assert.Contains(t, result, tt.contains)
		})
	}
}

func TestBuildWithWorkflowContext(t *testing.T) {
	ctx := &WorkflowContext{
		PRUrl:      "https://github.com/org/repo/pull/42",
		BranchName: "feature/login-fix",
		Status:     "COMPLETED",
	}

	result := NewSystemPromptBuilder().
		AppendPersona().
		WithWorkflowContext(ctx).
		Build()

	assert.Contains(t, result, "## Prior Automated Work")
	assert.Contains(t, result, "https://github.com/org/repo/pull/42")
	assert.Contains(t, result, "feature/login-fix")
	assert.Contains(t, result, "COMPLETED")
}

func TestBuildWithWorkflowContext_NoBranch(t *testing.T) {
	ctx := &WorkflowContext{
		PRUrl:  "https://github.com/org/repo/pull/99",
		Status: "IN_PROGRESS",
	}

	result := NewSystemPromptBuilder().WithWorkflowContext(ctx).Build()

	assert.Contains(t, result, "https://github.com/org/repo/pull/99")
	assert.NotContains(t, result, "**Branch:**")
}

func TestBuildWithoutWorkflowContext(t *testing.T) {
	result := NewSystemPromptBuilder().AppendPersona().Build()

	assert.NotContains(t, result, "Prior Automated Work")
}

func TestTruncate(t *testing.T) {
	assert.Equal(t, "abc", truncate("abc", 10))
	assert.Equal(t, "abcde\u2026", truncate("abcdefghij", 5))
	assert.Equal(t, "", truncate("", 5))
}

func TestFullPromptBuild(t *testing.T) {
	req := model.AgentRequest{
		TicketKey:     "PROJ-1",
		Platform:      "jira",
		CommentAuthor: "tester",
	}

	result := NewSystemPromptBuilder().
		AppendPersona().
		AppendContext(&req).
		AppendIntentGuidelines(model.IntentAICommand).
		Build()

	require.Contains(t, result, "expert AI development assistant")
	require.Contains(t, result, "- Ticket: PROJ-1")
	require.Contains(t, result, "## Guidelines")
}
