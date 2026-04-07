package mcp

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/tool"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	"github.com/rs/zerolog/log"
)

const gatewayTimeout = 10 * time.Second

// GatewayClient is an HTTP client for a unified MCP Gateway that provides
// access to multiple MCP servers (context7, github, jira, etc.).
type GatewayClient struct {
	gatewayURL  string
	namespace   string
	httpClient  *http.Client
	cachedTools []tool.Tool
}

// NewGatewayClient creates a gateway client for a specific namespace.
func NewGatewayClient(gatewayURL, namespace string) *GatewayClient {
	return &GatewayClient{
		gatewayURL: strings.TrimRight(gatewayURL, "/"),
		namespace:  namespace,
		httpClient: &http.Client{Timeout: gatewayTimeout},
	}
}

func (g *GatewayClient) Namespace() string   { return g.namespace }
func (g *GatewayClient) MaxOutputChars() int { return bridgeMaxOutputChars }

// ToolDefinitions discovers and returns tools from the gateway. Results are cached.
func (g *GatewayClient) ToolDefinitions() []tool.Tool {
	if g.cachedTools != nil {
		return g.cachedTools
	}

	ctx := context.Background()
	u := fmt.Sprintf("%s/%s/tools", g.gatewayURL, g.namespace)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, http.NoBody)
	if err != nil {
		log.Error().Err(err).Str("namespace", g.namespace).Msg("create tools request failed")
		return nil
	}

	resp, err := g.httpClient.Do(req)
	if err != nil {
		log.Error().Err(err).Str("namespace", g.namespace).Msg("fetch tools from gateway failed")
		return nil
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		log.Error().Err(err).Str("namespace", g.namespace).Msg("read tools response failed")
		return nil
	}

	var toolsResp struct {
		Tools []struct {
			Name        string         `json:"name"`
			Description string         `json:"description"`
			InputSchema map[string]any `json:"inputSchema"`
		} `json:"tools"`
	}
	if err := json.Unmarshal(body, &toolsResp); err != nil {
		log.Error().Err(err).Str("namespace", g.namespace).Msg("parse tools response failed")
		return nil
	}

	tools := make([]tool.Tool, 0, len(toolsResp.Tools))
	for _, t := range toolsResp.Tools {
		schema := t.InputSchema
		if schema == nil {
			schema = map[string]any{"type": "object", "properties": map[string]any{}}
		}
		tools = append(tools, tool.Tool{
			Name:        g.namespace + "_" + t.Name,
			Description: t.Description,
			InputSchema: schema,
		})
	}

	g.cachedTools = tools
	log.Info().Str("namespace", g.namespace).Int("tools", len(tools)).Msg("gateway tools discovered")
	return tools
}

// Execute calls a tool via the MCP gateway.
func (g *GatewayClient) Execute(ctx context.Context, _ *spi.OperationContext, call tool.Call) tool.Result {
	// Strip namespace prefix from tool name
	prefix := g.namespace + "_"
	toolName := strings.TrimPrefix(call.Name, prefix)

	args := call.Input
	if args == nil {
		args = make(map[string]any)
	}

	reqBody := map[string]any{
		"tool":      toolName,
		"arguments": args,
	}
	jsonBody, err := json.Marshal(reqBody)
	if err != nil {
		return tool.Error(call.ID, fmt.Sprintf("marshal request: %v", err))
	}

	u := fmt.Sprintf("%s/%s/call", g.gatewayURL, g.namespace)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(jsonBody))
	if err != nil {
		return tool.Error(call.ID, fmt.Sprintf("create request: %v", err))
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := g.httpClient.Do(req)
	if err != nil {
		return tool.Error(call.ID, fmt.Sprintf("gateway call failed: %v", err))
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return tool.Error(call.ID, fmt.Sprintf("read response: %v", err))
	}

	var result struct {
		Result struct {
			IsError bool `json:"isError"`
			Content []struct {
				Type string `json:"type"`
				Text string `json:"text"`
			} `json:"content"`
		} `json:"result"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return tool.Error(call.ID, fmt.Sprintf("parse response: %v", err))
	}

	var parts []string
	for _, c := range result.Result.Content {
		if c.Type == "text" {
			parts = append(parts, c.Text)
		}
	}
	text := strings.Join(parts, "\n")

	if result.Result.IsError {
		return tool.Error(call.ID, text)
	}
	return tool.Success(call.ID, text)
}

// CreateAllClients discovers all namespaces from the gateway and creates clients.
func CreateAllClients(gatewayURL string) []*GatewayClient {
	ctx := context.Background()
	u := strings.TrimRight(gatewayURL, "/") + "/namespaces"

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, http.NoBody)
	if err != nil {
		log.Error().Err(err).Msg("create namespaces request failed")
		return nil
	}

	client := &http.Client{Timeout: gatewayTimeout}
	resp, err := client.Do(req)
	if err != nil {
		log.Error().Err(err).Msg("fetch namespaces from gateway failed")
		return nil
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		log.Error().Err(err).Msg("read namespaces response failed")
		return nil
	}

	var nsResp struct {
		Namespaces []string `json:"namespaces"`
	}
	if err := json.Unmarshal(body, &nsResp); err != nil {
		log.Error().Err(err).Msg("parse namespaces response failed")
		return nil
	}

	clients := make([]*GatewayClient, 0, len(nsResp.Namespaces))
	for _, ns := range nsResp.Namespaces {
		clients = append(clients, NewGatewayClient(gatewayURL, ns))
	}
	return clients
}
