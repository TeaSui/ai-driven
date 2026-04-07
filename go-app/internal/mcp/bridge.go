package mcp

import (
	"context"
	"fmt"
	"regexp"
	"strings"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/tool"
	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
	mcpclient "github.com/mark3labs/mcp-go/client"
	mcpgo "github.com/mark3labs/mcp-go/mcp"
	"github.com/rs/zerolog/log"
)

const bridgeMaxOutputChars = 30000

var sanitizeRe = regexp.MustCompile(`[^a-zA-Z0-9_]+`)

// BridgeToolProvider bridges an MCP server into the agent's ToolProvider contract.
// It discovers tools on construction and caches them.
type BridgeToolProvider struct {
	namespace       string
	client          *mcpclient.Client
	cachedTools     []tool.Tool
	toolNameMapping map[string]string // prefixed name → original MCP name
}

// NewBridgeToolProvider creates a provider that wraps an MCP client.
// It calls listTools during construction to discover available tools.
func NewBridgeToolProvider(namespace string, client *mcpclient.Client) (*BridgeToolProvider, error) {
	p := &BridgeToolProvider{
		namespace:       namespace,
		client:          client,
		toolNameMapping: make(map[string]string),
	}

	tools, err := p.discoverTools(context.Background())
	if err != nil {
		return nil, fmt.Errorf("discover MCP tools for %s: %w", namespace, err)
	}
	p.cachedTools = tools

	log.Info().Str("namespace", namespace).Int("tools", len(tools)).Msg("MCP bridge initialized")
	return p, nil
}

func (p *BridgeToolProvider) Namespace() string            { return p.namespace }
func (p *BridgeToolProvider) ToolDefinitions() []tool.Tool { return p.cachedTools }
func (p *BridgeToolProvider) MaxOutputChars() int          { return bridgeMaxOutputChars }

// Execute runs a tool call against the MCP server.
func (p *BridgeToolProvider) Execute(ctx context.Context, _ *spi.OperationContext, call tool.Call) tool.Result {
	originalName, ok := p.toolNameMapping[call.Name]
	if !ok {
		return tool.Error(call.ID, fmt.Sprintf("unknown tool: %s", call.Name))
	}

	args := call.Input
	if args == nil {
		args = make(map[string]any)
	}

	result, err := p.client.CallTool(ctx, mcpgo.CallToolRequest{
		Params: mcpgo.CallToolParams{
			Name:      originalName,
			Arguments: args,
		},
	})
	if err != nil {
		return tool.Error(call.ID, fmt.Sprintf("MCP call failed: %v", err))
	}

	return convertResult(call.ID, result)
}

// Client returns the underlying MCP client.
func (p *BridgeToolProvider) Client() *mcpclient.Client {
	return p.client
}

func (p *BridgeToolProvider) discoverTools(ctx context.Context) ([]tool.Tool, error) {
	result, err := p.client.ListTools(ctx, mcpgo.ListToolsRequest{})
	if err != nil {
		return nil, err
	}

	tools := make([]tool.Tool, 0, len(result.Tools))
	for i := range result.Tools {
		t := p.convertMcpTool(&result.Tools[i])
		tools = append(tools, t)
	}
	return tools, nil
}

func (p *BridgeToolProvider) convertMcpTool(mcpTool *mcpgo.Tool) tool.Tool {
	sanitized := sanitizeToolName(mcpTool.Name)
	prefixed := p.namespace + "_" + sanitized

	p.toolNameMapping[prefixed] = mcpTool.Name

	desc := mcpTool.Description
	if desc == "" {
		desc = "MCP tool: " + mcpTool.Name
	}

	schema := convertInputSchema(mcpTool.InputSchema)

	return tool.Tool{
		Name:        prefixed,
		Description: desc,
		InputSchema: schema,
	}
}

func convertInputSchema(mcpSchema mcpgo.ToolInputSchema) map[string]any {
	schema := map[string]any{
		"type": "object",
	}
	if len(mcpSchema.Properties) > 0 {
		schema["properties"] = mcpSchema.Properties
	}
	if len(mcpSchema.Required) > 0 {
		schema["required"] = mcpSchema.Required
	}
	return schema
}

func convertResult(callID string, result *mcpgo.CallToolResult) tool.Result {
	text := extractTextContent(result)
	if result.IsError {
		return tool.Error(callID, text)
	}
	return tool.Success(callID, text)
}

func extractTextContent(result *mcpgo.CallToolResult) string {
	var parts []string
	for _, content := range result.Content {
		if tc, ok := mcpgo.AsTextContent(content); ok {
			parts = append(parts, tc.Text)
		}
	}
	return strings.Join(parts, "\n")
}

// sanitizeToolName replaces non-alphanumeric chars with underscores.
func sanitizeToolName(name string) string {
	s := sanitizeRe.ReplaceAllString(name, "_")
	s = strings.Trim(s, "_")
	return s
}
