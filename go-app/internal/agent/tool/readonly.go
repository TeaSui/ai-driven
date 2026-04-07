package tool

import (
	"context"
	"fmt"
	"strings"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// DeniedKeywords are substrings that indicate a tool performs write operations.
var DeniedKeywords = []string{
	"create", "commit", "push", "delete", "update", "write", "post", "apply",
}

// ReadOnlyProvider wraps a Provider and filters out write-oriented tools.
type ReadOnlyProvider struct {
	Delegate Provider
}

// NewReadOnlyProvider creates a new ReadOnlyProvider wrapping the given delegate.
func NewReadOnlyProvider(delegate Provider) *ReadOnlyProvider {
	return &ReadOnlyProvider{Delegate: delegate}
}

// Namespace returns the delegate's namespace.
func (p *ReadOnlyProvider) Namespace() string {
	return p.Delegate.Namespace()
}

// ToolDefinitions returns only the read-only tools from the delegate.
func (p *ReadOnlyProvider) ToolDefinitions() []Tool {
	all := p.Delegate.ToolDefinitions()
	var readOnly []Tool
	for _, t := range all {
		if isReadOnly(t.Name) {
			readOnly = append(readOnly, t)
		}
	}
	return readOnly
}

// Execute runs the tool if it is read-only, otherwise returns an error.
func (p *ReadOnlyProvider) Execute(ctx context.Context, op *spi.OperationContext, call Call) Result {
	if !isReadOnly(call.Name) {
		return Error(call.ID, fmt.Sprintf("tool '%s' is not allowed in read-only mode", call.Name))
	}
	return p.Delegate.Execute(ctx, op, call)
}

// MaxOutputChars returns the delegate's MaxOutputChars.
func (p *ReadOnlyProvider) MaxOutputChars() int {
	return p.Delegate.MaxOutputChars()
}

// isReadOnly returns true if the tool name does not contain any denied keywords (case-insensitive).
func isReadOnly(toolName string) bool {
	lower := strings.ToLower(toolName)
	for _, kw := range DeniedKeywords {
		if strings.Contains(lower, kw) {
			return false
		}
	}
	return true
}
