package main

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestCosineSimilar_IdenticalTexts(t *testing.T) {
	assert.True(t, cosineSimilar("hello world foo bar", "hello world foo bar"))
}

func TestCosineSimilar_HighOverlap(t *testing.T) {
	assert.True(t, cosineSimilar(
		"I analyzed the code and found a bug in the login handler",
		"I analyzed the code and found a bug in the authentication handler",
	))
}

func TestCosineSimilar_LowOverlap(t *testing.T) {
	assert.False(t, cosineSimilar(
		"The weather is nice today",
		"I found a critical security vulnerability",
	))
}

func TestCosineSimilar_EmptyTexts(t *testing.T) {
	assert.True(t, cosineSimilar("", ""))
}

func TestCosineSimilar_OneEmpty(t *testing.T) {
	assert.False(t, cosineSimilar("hello world", ""))
	assert.False(t, cosineSimilar("", "hello world"))
}

func TestSameTools_Match(t *testing.T) {
	assert.True(t, sameTools(
		[]string{"jira_get_ticket", "github_search"},
		[]string{"github_search", "jira_get_ticket"},
	))
}

func TestSameTools_Mismatch(t *testing.T) {
	assert.False(t, sameTools(
		[]string{"jira_get_ticket"},
		[]string{"github_search"},
	))
}

func TestSameTools_DifferentLength(t *testing.T) {
	assert.False(t, sameTools(
		[]string{"jira_get_ticket", "github_search"},
		[]string{"jira_get_ticket"},
	))
}

func TestSameTools_Empty(t *testing.T) {
	assert.True(t, sameTools(nil, nil))
	assert.True(t, sameTools([]string{}, []string{}))
}

func TestCompare_AllMatching(t *testing.T) {
	java := map[string]agentResponse{
		"PROJ-1": {TicketKey: "PROJ-1", Text: "analysis of the bug", ToolsUsed: []string{"jira"}, TokenCount: 1000, TurnCount: 2},
	}
	goResp := map[string]agentResponse{
		"PROJ-1": {TicketKey: "PROJ-1", Text: "analysis of the bug", ToolsUsed: []string{"jira"}, TokenCount: 1050, TurnCount: 2},
	}

	report := compare(java, goResp)
	assert.Equal(t, 1, report.Total)
	assert.Equal(t, 1, report.Matches)
	assert.Equal(t, 0, report.Failures)
}

func TestCompare_Mismatch(t *testing.T) {
	java := map[string]agentResponse{
		"PROJ-1": {TicketKey: "PROJ-1", Text: "I fixed the bug", ToolsUsed: []string{"jira"}, TokenCount: 1000},
	}
	goResp := map[string]agentResponse{
		"PROJ-1": {TicketKey: "PROJ-1", Text: "totally different response about weather", ToolsUsed: []string{"github"}, TokenCount: 5000},
	}

	report := compare(java, goResp)
	assert.Equal(t, 1, report.Failures)
}

func TestCompare_JavaOnly(t *testing.T) {
	java := map[string]agentResponse{
		"PROJ-1": {TicketKey: "PROJ-1", Text: "response"},
	}
	goResp := map[string]agentResponse{}

	report := compare(java, goResp)
	assert.Equal(t, 1, report.JavaOnly)
}

func TestCompare_GoOnly(t *testing.T) {
	java := map[string]agentResponse{}
	goResp := map[string]agentResponse{
		"PROJ-1": {TicketKey: "PROJ-1", Text: "response"},
	}

	report := compare(java, goResp)
	assert.Equal(t, 1, report.GoOnly)
}

func TestTruncate(t *testing.T) {
	assert.Equal(t, "short", truncate("short", 10))
	assert.Equal(t, "long s...", truncate("long string here", 9))
}
