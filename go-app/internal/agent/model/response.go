package model

// AgentResponse is the output from the agent orchestrator.
type AgentResponse struct {
	Text       string   `json:"text"`
	ToolsUsed  []string `json:"toolsUsed"`
	TokenCount int      `json:"tokenCount"`
	TurnCount  int      `json:"turnCount"`
}
