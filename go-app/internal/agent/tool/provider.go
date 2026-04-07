package tool

import (
	"context"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// DefaultMaxOutputChars is the default maximum output length for tool results.
const DefaultMaxOutputChars = 20000

// Provider defines an interface for a set of related tools grouped under a namespace.
type Provider interface {
	// Namespace returns the prefix namespace for all tools in this provider.
	Namespace() string

	// ToolDefinitions returns the list of tools offered by this provider.
	ToolDefinitions() []Tool

	// Execute runs a specific tool call and returns the result.
	Execute(ctx context.Context, op *spi.OperationContext, call Call) Result

	// MaxOutputChars returns the maximum number of characters allowed in tool output.
	MaxOutputChars() int
}
