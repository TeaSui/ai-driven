package mcp

import (
	"context"
	"fmt"
	"time"

	mcpclient "github.com/mark3labs/mcp-go/client"
	mcpgo "github.com/mark3labs/mcp-go/mcp"
	"github.com/rs/zerolog/log"
)

const (
	clientName    = "ai-driven-agent"
	clientVersion = "1.0.0"
	initTimeout   = 60 * time.Second
)

// ServerConfig holds configuration for connecting to an MCP server.
type ServerConfig struct {
	Namespace string            `json:"namespace"`
	Transport string            `json:"transport"` // "stdio" or "http"
	Command   string            `json:"command,omitempty"`
	Args      []string          `json:"args,omitempty"`
	URL       string            `json:"url,omitempty"`
	Env       map[string]string `json:"env,omitempty"`
	Enabled   bool              `json:"enabled"`
}

// Validate checks the config is complete for its transport type.
func (c *ServerConfig) Validate() error {
	if c.Namespace == "" {
		return fmt.Errorf("MCP server config: namespace is required")
	}
	if c.Transport == "" {
		return fmt.Errorf("MCP server config %s: transport is required", c.Namespace)
	}
	switch c.Transport {
	case "stdio":
		if c.Command == "" {
			return fmt.Errorf("MCP server config %s: command required for stdio transport", c.Namespace)
		}
	case "http":
		if c.URL == "" {
			return fmt.Errorf("MCP server config %s: url required for http transport", c.Namespace)
		}
	default:
		return fmt.Errorf("MCP server config %s: unsupported transport '%s'", c.Namespace, c.Transport)
	}
	return nil
}

// Connect creates and initializes an MCP client from config.
func Connect(ctx context.Context, cfg *ServerConfig) (*mcpclient.Client, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}

	var (
		client *mcpclient.Client
		err    error
	)

	switch cfg.Transport {
	case "stdio":
		env := buildEnv(cfg.Env)
		client, err = mcpclient.NewStdioMCPClient(cfg.Command, env, cfg.Args...)
		if err != nil {
			return nil, fmt.Errorf("create stdio MCP client for %s: %w", cfg.Namespace, err)
		}
	case "http":
		client, err = mcpclient.NewStreamableHttpClient(cfg.URL)
		if err != nil {
			return nil, fmt.Errorf("create HTTP MCP client for %s: %w", cfg.Namespace, err)
		}
	}

	initCtx, cancel := context.WithTimeout(ctx, initTimeout)
	defer cancel()

	_, err = client.Initialize(initCtx, mcpgo.InitializeRequest{
		Params: mcpgo.InitializeParams{
			ProtocolVersion: mcpgo.LATEST_PROTOCOL_VERSION,
			ClientInfo: mcpgo.Implementation{
				Name:    clientName,
				Version: clientVersion,
			},
		},
	})
	if err != nil {
		client.Close()
		return nil, fmt.Errorf("initialize MCP client for %s: %w", cfg.Namespace, err)
	}

	log.Info().Str("namespace", cfg.Namespace).Str("transport", cfg.Transport).Msg("MCP client connected")
	return client, nil
}

// ConnectAndCreateProvider connects to an MCP server and wraps it as a BridgeToolProvider.
func ConnectAndCreateProvider(ctx context.Context, cfg *ServerConfig) (*BridgeToolProvider, error) {
	client, err := Connect(ctx, cfg)
	if err != nil {
		return nil, err
	}
	return NewBridgeToolProvider(cfg.Namespace, client)
}

// CloseQuietly safely closes an MCP client, logging any errors.
func CloseQuietly(client *mcpclient.Client, namespace string) {
	if client == nil {
		return
	}
	if err := client.Close(); err != nil {
		log.Warn().Err(err).Str("namespace", namespace).Msg("error closing MCP client")
	}
}

// buildEnv converts a map to KEY=VALUE slice.
func buildEnv(env map[string]string) []string {
	if len(env) == 0 {
		return nil
	}
	result := make([]string, 0, len(env))
	for k, v := range env {
		result = append(result, k+"="+v)
	}
	return result
}
