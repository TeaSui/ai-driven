package mcp

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/tool"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGatewayClient_ToolDefinitions(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/github/tools", func(w http.ResponseWriter, _ *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"tools": []map[string]any{
				{
					"name":        "list_repos",
					"description": "List repositories",
					"inputSchema": map[string]any{"type": "object", "properties": map[string]any{}},
				},
				{
					"name":        "get_file",
					"description": "Get file content",
					"inputSchema": map[string]any{"type": "object"},
				},
			},
		})
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)

	g := NewGatewayClient(server.URL, "github")
	tools := g.ToolDefinitions()
	require.Len(t, tools, 2)
	assert.Equal(t, "github_list_repos", tools[0].Name)
	assert.Equal(t, "github_get_file", tools[1].Name)
	assert.Equal(t, "List repositories", tools[0].Description)

	// Verify caching
	tools2 := g.ToolDefinitions()
	assert.Equal(t, tools, tools2)
}

func TestGatewayClient_Execute_Success(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/github/call", func(w http.ResponseWriter, r *http.Request) {
		var req map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&req))
		assert.Equal(t, "get_file", req["tool"])
		args := req["arguments"].(map[string]any)
		assert.Equal(t, "main.go", args["path"])

		json.NewEncoder(w).Encode(map[string]any{
			"result": map[string]any{
				"isError": false,
				"content": []map[string]string{
					{"type": "text", "text": "package main"},
				},
			},
		})
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)

	g := NewGatewayClient(server.URL, "github")
	result := g.Execute(t.Context(), nil, tool.Call{
		ID:    "call-1",
		Name:  "github_get_file",
		Input: map[string]any{"path": "main.go"},
	})
	assert.False(t, result.IsError)
	assert.Equal(t, "package main", result.Content)
}

func TestGatewayClient_Execute_Error(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/github/call", func(w http.ResponseWriter, _ *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"result": map[string]any{
				"isError": true,
				"content": []map[string]string{
					{"type": "text", "text": "file not found"},
				},
			},
		})
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)

	g := NewGatewayClient(server.URL, "github")
	result := g.Execute(t.Context(), nil, tool.Call{
		ID:   "call-2",
		Name: "github_get_file",
	})
	assert.True(t, result.IsError)
	assert.Equal(t, "file not found", result.Content)
}

func TestGatewayClient_Namespace(t *testing.T) {
	g := NewGatewayClient("http://localhost:8080", "jira")
	assert.Equal(t, "jira", g.Namespace())
	assert.Equal(t, bridgeMaxOutputChars, g.MaxOutputChars())
}

func TestCreateAllClients(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/namespaces", func(w http.ResponseWriter, _ *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"namespaces": []string{"github", "jira", "context7"},
		})
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)

	clients := CreateAllClients(server.URL)
	require.Len(t, clients, 3)
	assert.Equal(t, "github", clients[0].Namespace())
	assert.Equal(t, "jira", clients[1].Namespace())
	assert.Equal(t, "context7", clients[2].Namespace())
}

func TestCreateAllClients_Error(t *testing.T) {
	clients := CreateAllClients("http://localhost:1")
	assert.Nil(t, clients)
}
