package agent

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
	"github.com/AirdropToTheMoon/ai-driven/internal/repository"
)

type ConversationWindowManager struct {
	repo         repository.ConversationRepository
	tokenBudget  int
	recentToKeep int
}

func NewConversationWindowManager(repo repository.ConversationRepository, tokenBudget, recentToKeep int) *ConversationWindowManager {
	return &ConversationWindowManager{
		repo:         repo,
		tokenBudget:  tokenBudget,
		recentToKeep: recentToKeep,
	}
}

func (m *ConversationWindowManager) BuildMessages(ctx context.Context, tenantID, ticketKey string) ([]map[string]any, error) {
	allMessages, err := m.repo.GetConversation(ctx, tenantID, ticketKey)
	if err != nil {
		return nil, fmt.Errorf("get conversation: %w", err)
	}

	selected := m.selectWithinBudget(allMessages)
	return toAPIFormat(ctx, selected), nil
}

func (m *ConversationWindowManager) AppendAndBuild(ctx context.Context, tenantID, ticketKey string, msg *model.ConversationMessage) ([]map[string]any, error) {
	if err := m.repo.Save(ctx, msg); err != nil {
		return nil, fmt.Errorf("save message: %w", err)
	}

	return m.BuildMessages(ctx, tenantID, ticketKey)
}

func (m *ConversationWindowManager) selectWithinBudget(allMessages []model.ConversationMessage) []model.ConversationMessage {
	if len(allMessages) == 0 {
		return nil
	}

	// Always keep the last N messages
	recentStart := len(allMessages) - m.recentToKeep
	if recentStart < 0 {
		recentStart = 0
	}

	recent := allMessages[recentStart:]
	older := allMessages[:recentStart]

	// Calculate tokens used by recent messages
	recentTokens := 0
	for i := range recent {
		recentTokens += recent[i].TokenCount
	}

	remainingBudget := m.tokenBudget - recentTokens
	if remainingBudget <= 0 {
		return recent
	}

	// From older messages, iterate newest -> oldest, include as many as fit
	var olderThatFit []model.ConversationMessage
	tokensUsed := 0
	for i := len(older) - 1; i >= 0; i-- {
		if tokensUsed+older[i].TokenCount > remainingBudget {
			break
		}
		tokensUsed += older[i].TokenCount
		olderThatFit = append(olderThatFit, older[i])
	}

	// Reverse olderThatFit to restore chronological order
	for i, j := 0, len(olderThatFit)-1; i < j; i, j = i+1, j-1 {
		olderThatFit[i], olderThatFit[j] = olderThatFit[j], olderThatFit[i]
	}

	return append(olderThatFit, recent...)
}

func toAPIFormat(ctx context.Context, messages []model.ConversationMessage) []map[string]any {
	raw := make([]map[string]any, 0, len(messages))
	for i := range messages {
		content := parseContent(ctx, messages[i].ContentJSON)
		raw = append(raw, map[string]any{
			"role":    messages[i].Role,
			"content": content,
		})
	}
	return mergeConsecutiveRoles(raw)
}

func parseContent(ctx context.Context, contentJSON string) any {
	var blocks []map[string]any
	if err := json.Unmarshal([]byte(contentJSON), &blocks); err != nil {
		log.Ctx(ctx).Debug().Err(err).Msg("Content is not JSON array, treating as plain text")
		return contentJSON
	}
	return blocks
}

func mergeConsecutiveRoles(messages []map[string]any) []map[string]any {
	if len(messages) == 0 {
		return nil
	}

	var merged []map[string]any
	merged = append(merged, messages[0])

	for i := 1; i < len(messages); i++ {
		prev := merged[len(merged)-1]
		curr := messages[i]

		prevRole, ok1 := prev["role"].(string)
		currRole, ok2 := curr["role"].(string)
		if !ok1 || !ok2 {
			merged = append(merged, curr)
			continue
		}

		if prevRole == currRole {
			prev["content"] = mergeContent(prev["content"], curr["content"])
		} else {
			merged = append(merged, curr)
		}
	}
	return merged
}

func mergeContent(a, b any) any {
	aList := toContentList(a)
	bList := toContentList(b)
	return append(aList, bList...)
}

func toContentList(content any) []map[string]any {
	switch v := content.(type) {
	case []map[string]any:
		return v
	case []any:
		var result []map[string]any
		for _, item := range v {
			if m, ok := item.(map[string]any); ok {
				result = append(result, m)
			}
		}
		return result
	case string:
		return []map[string]any{
			{"type": "text", "text": v},
		}
	default:
		return nil
	}
}
