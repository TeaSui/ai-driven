package tool

import (
	"context"
	"fmt"
	"sort"
	"strings"

	"github.com/AirdropToTheMoon/ai-driven/internal/spi"
)

// CoreNamespaces defines the namespaces considered core to the system.
var CoreNamespaces = map[string]bool{
	"source_control": true,
	"issue_tracker":  true,
	"code_context":   true,
}

// Registry manages tool providers and routes tool calls to the correct provider.
type Registry struct {
	providers  map[string]Provider
	namespaces []string // ordered by registration
}

// NewRegistry creates a new empty Registry.
func NewRegistry() *Registry {
	return &Registry{
		providers: make(map[string]Provider),
	}
}

// Register adds a Provider to the registry under its namespace.
func (r *Registry) Register(provider Provider) {
	ns := provider.Namespace()
	if _, exists := r.providers[ns]; !exists {
		r.namespaces = append(r.namespaces, ns)
	}
	r.providers[ns] = provider
}

// GetAllToolDefinitions returns all tool definitions from all registered providers.
func (r *Registry) GetAllToolDefinitions() []Tool {
	var tools []Tool
	for _, ns := range r.namespaces {
		p := r.providers[ns]
		tools = append(tools, p.ToolDefinitions()...)
	}
	return tools
}

// Execute routes a tool call to the appropriate provider and returns the result.
// It catches panics and truncates output that exceeds the provider's MaxOutputChars.
func (r *Registry) Execute(ctx context.Context, op *spi.OperationContext, call Call) (result Result) {
	ns := r.ExtractNamespace(call.Name)
	provider, ok := r.providers[ns]
	if !ok {
		return Error(call.ID, fmt.Sprintf("no provider registered for tool '%s' (namespace '%s')", call.Name, ns))
	}

	// Catch panics from provider execution.
	defer func() {
		if rec := recover(); rec != nil {
			result = Error(call.ID, fmt.Sprintf("panic during tool execution: %v", rec))
		}
	}()

	result = provider.Execute(ctx, op, call)

	// Truncate output if it exceeds the provider's limit.
	maxChars := provider.MaxOutputChars()
	if maxChars > 0 && len(result.Content) > maxChars {
		totalLen := len(result.Content)
		result.Content = result.Content[:maxChars] +
			fmt.Sprintf("\n[OUTPUT TRUNCATED — %d total chars]", totalLen)
	}

	return result
}

// ExtractNamespace finds the longest registered namespace that is a prefix of the tool name.
// Tool names are expected to follow the pattern "namespace_toolname".
func (r *Registry) ExtractNamespace(toolName string) string {
	bestMatch := ""
	for _, ns := range r.namespaces {
		prefix := ns + "_"
		if strings.HasPrefix(toolName, prefix) && len(ns) > len(bestMatch) {
			bestMatch = ns
		}
	}
	return bestMatch
}

// RegisteredNamespaces returns a sorted list of all registered namespace names.
func (r *Registry) RegisteredNamespaces() []string {
	result := make([]string, len(r.namespaces))
	copy(result, r.namespaces)
	sort.Strings(result)
	return result
}
