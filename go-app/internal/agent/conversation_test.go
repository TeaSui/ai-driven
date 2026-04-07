package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

// --- In-memory mock repository ---

type mockConversationRepository struct {
	mu       sync.Mutex
	messages map[string][]model.ConversationMessage // key = PK
}

func newMockConversationRepository() *mockConversationRepository {
	return &mockConversationRepository{
		messages: make(map[string][]model.ConversationMessage),
	}
}

func (r *mockConversationRepository) Save(_ context.Context, msg *model.ConversationMessage) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.messages[msg.PK] = append(r.messages[msg.PK], *msg)
	return nil
}

func (r *mockConversationRepository) GetConversation(_ context.Context, tenantID, ticketKey string) ([]model.ConversationMessage, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	pk := model.CreateConversationPK(tenantID, ticketKey)
	msgs := r.messages[pk]
	result := make([]model.ConversationMessage, len(msgs))
	copy(result, msgs)
	return result, nil
}

func (r *mockConversationRepository) GetTotalTokens(_ context.Context, tenantID, ticketKey string) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	pk := model.CreateConversationPK(tenantID, ticketKey)
	total := 0
	for _, m := range r.messages[pk] {
		total += m.TokenCount
	}
	return total, nil
}

func (r *mockConversationRepository) DeleteConversation(_ context.Context, tenantID, ticketKey string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	pk := model.CreateConversationPK(tenantID, ticketKey)
	delete(r.messages, pk)
	return nil
}

// --- Helpers ---

func makeMessage(role, text string, tokenCount int, seqOffset int) model.ConversationMessage {
	contentJSON, _ := json.Marshal([]map[string]any{
		{"type": "text", "text": text},
	})
	now := time.Now().Add(time.Duration(seqOffset) * time.Second)
	return model.ConversationMessage{
		PK:          model.CreateConversationPK("tenant1", "PROJ-1"),
		SK:          model.CreateConversationSK(now, seqOffset),
		Role:        role,
		Author:      "test",
		ContentJSON: string(contentJSON),
		Timestamp:   now,
		TokenCount:  tokenCount,
		TTL:         model.DefaultTTL(),
	}
}

// --- Tests ---

func TestConversationWindowManager_SelectWithinBudget_AllFit(t *testing.T) {
	mgr := NewConversationWindowManager(nil, 1000, 2)

	messages := []model.ConversationMessage{
		makeMessage("user", "msg1", 100, 0),
		makeMessage("assistant", "msg2", 100, 1),
		makeMessage("user", "msg3", 100, 2),
		makeMessage("assistant", "msg4", 100, 3),
	}

	selected := mgr.selectWithinBudget(messages)
	assert.Len(t, selected, 4)
}

func TestConversationWindowManager_SelectWithinBudget_PruneOld(t *testing.T) {
	mgr := NewConversationWindowManager(nil, 250, 2)

	messages := []model.ConversationMessage{
		makeMessage("user", "old1", 100, 0),
		makeMessage("assistant", "old2", 100, 1),
		makeMessage("user", "old3", 100, 2),
		makeMessage("assistant", "recent1", 100, 3),
		makeMessage("user", "recent2", 100, 4),
	}

	selected := mgr.selectWithinBudget(messages)
	// Recent: recent1 (100) + recent2 (100) = 200. Remaining budget: 50.
	// old3 (100) doesn't fit. So only recent messages kept.
	assert.Len(t, selected, 2)
	assertMessageText(t, selected[0], "recent1")
	assertMessageText(t, selected[1], "recent2")
}

func TestConversationWindowManager_SelectWithinBudget_OnlyRecent(t *testing.T) {
	mgr := NewConversationWindowManager(nil, 150, 2)

	messages := []model.ConversationMessage{
		makeMessage("user", "old", 500, 0),
		makeMessage("assistant", "recent1", 50, 1),
		makeMessage("user", "recent2", 50, 2),
	}

	selected := mgr.selectWithinBudget(messages)
	// Recent tokens: 100, remaining budget: 50, old (500) doesn't fit
	assert.Len(t, selected, 2)
	assertMessageText(t, selected[0], "recent1")
	assertMessageText(t, selected[1], "recent2")
}

func TestConversationWindowManager_SelectWithinBudget_PartialOld(t *testing.T) {
	mgr := NewConversationWindowManager(nil, 350, 2)

	messages := []model.ConversationMessage{
		makeMessage("user", "old1", 100, 0),
		makeMessage("assistant", "old2", 100, 1),
		makeMessage("user", "recent1", 100, 2),
		makeMessage("assistant", "recent2", 100, 3),
	}

	selected := mgr.selectWithinBudget(messages)
	// Recent: 200 tokens, remaining: 150. old2 (100) fits, old1 (100) would exceed.
	assert.Len(t, selected, 3)
	assertMessageText(t, selected[0], "old2")
	assertMessageText(t, selected[1], "recent1")
	assertMessageText(t, selected[2], "recent2")
}

func TestConversationWindowManager_SelectWithinBudget_Empty(t *testing.T) {
	mgr := NewConversationWindowManager(nil, 1000, 2)

	selected := mgr.selectWithinBudget(nil)
	assert.Nil(t, selected)
}

func TestConversationWindowManager_BuildMessages_MergeConsecutiveRoles(t *testing.T) {
	repo := newMockConversationRepository()
	mgr := NewConversationWindowManager(repo, 10000, 10)

	ctx := t.Context()
	pk := model.CreateConversationPK("tenant1", "PROJ-1")

	// Save messages with consecutive user roles
	for i, msg := range []model.ConversationMessage{
		{PK: pk, SK: fmt.Sprintf("MSG#%04d", 0), Role: "user", ContentJSON: `[{"type":"text","text":"first"}]`, TokenCount: 10},
		{PK: pk, SK: fmt.Sprintf("MSG#%04d", 1), Role: "user", ContentJSON: `[{"type":"text","text":"second"}]`, TokenCount: 10},
		{PK: pk, SK: fmt.Sprintf("MSG#%04d", 2), Role: "assistant", ContentJSON: `[{"type":"text","text":"reply"}]`, TokenCount: 10},
	} {
		_ = i
		require.NoError(t, repo.Save(ctx, &msg))
	}

	messages, err := mgr.BuildMessages(ctx, "tenant1", "PROJ-1")
	require.NoError(t, err)

	// Two consecutive user messages should be merged into one
	assert.Len(t, messages, 2)
	assert.Equal(t, "user", messages[0]["role"])
	assert.Equal(t, "assistant", messages[1]["role"])

	// Merged content should have 2 text blocks
	content, ok := messages[0]["content"].([]map[string]any)
	require.True(t, ok)
	assert.Len(t, content, 2)
}

func TestConversationWindowManager_AppendAndBuild(t *testing.T) {
	repo := newMockConversationRepository()
	mgr := NewConversationWindowManager(repo, 10000, 10)

	ctx := t.Context()
	msg := makeMessage("user", "hello", 10, 0)

	messages, err := mgr.AppendAndBuild(ctx, "tenant1", "PROJ-1", &msg)
	require.NoError(t, err)
	assert.Len(t, messages, 1)
	assert.Equal(t, "user", messages[0]["role"])
}

func TestMergeConsecutiveRoles(t *testing.T) {
	messages := []map[string]any{
		{"role": "user", "content": "hello"},
		{"role": "user", "content": "world"},
		{"role": "assistant", "content": []map[string]any{{"type": "text", "text": "response"}}},
	}

	merged := mergeConsecutiveRoles(messages)
	assert.Len(t, merged, 2)
	assert.Equal(t, "user", merged[0]["role"])
	assert.Equal(t, "assistant", merged[1]["role"])

	// First message content should be merged into list format
	content, ok := merged[0]["content"].([]map[string]any)
	require.True(t, ok)
	assert.Len(t, content, 2)
	assert.Equal(t, "hello", content[0]["text"])
	assert.Equal(t, "world", content[1]["text"])
}

func TestMergeConsecutiveRoles_Empty(t *testing.T) {
	assert.Nil(t, mergeConsecutiveRoles(nil))
}

func TestToContentList_String(t *testing.T) {
	result := toContentList("hello")
	require.Len(t, result, 1)
	assert.Equal(t, "text", result[0]["type"])
	assert.Equal(t, "hello", result[0]["text"])
}

func TestToContentList_MapSlice(t *testing.T) {
	input := []map[string]any{
		{"type": "text", "text": "block1"},
	}
	result := toContentList(input)
	assert.Equal(t, input, result)
}

func TestParseContent_ValidJSON(t *testing.T) {
	ctx := t.Context()
	content := parseContent(ctx, `[{"type":"text","text":"hi"}]`)
	blocks, ok := content.([]map[string]any)
	require.True(t, ok)
	assert.Len(t, blocks, 1)
}

func TestParseContent_PlainText(t *testing.T) {
	ctx := t.Context()
	content := parseContent(ctx, "just plain text")
	s, ok := content.(string)
	require.True(t, ok)
	assert.Equal(t, "just plain text", s)
}

// --- assertion helper ---

func assertMessageText(t *testing.T, msg model.ConversationMessage, expectedText string) {
	t.Helper()
	var blocks []map[string]any
	require.NoError(t, json.Unmarshal([]byte(msg.ContentJSON), &blocks))
	require.NotEmpty(t, blocks)
	assert.Equal(t, expectedText, blocks[0]["text"])
}
