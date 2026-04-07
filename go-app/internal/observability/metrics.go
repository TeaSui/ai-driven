package observability

import (
	"encoding/json"
	"fmt"
	"time"
)

// metricDef represents a single metric definition for CloudWatch EMF.
type metricDef struct {
	Name string `json:"Name"`
	Unit string `json:"Unit"`
}

// AgentMetrics collects metrics and properties, then flushes them as CloudWatch EMF JSON to stdout.
type AgentMetrics struct {
	properties map[string]any
	metrics    []metricDef
	dimensions [][]string
	now        func() time.Time // for testing
}

// NewAgentMetrics creates a new AgentMetrics instance with default dimensions.
func NewAgentMetrics() *AgentMetrics {
	return &AgentMetrics{
		properties: make(map[string]any),
		metrics:    make([]metricDef, 0),
		dimensions: [][]string{{"TenantId", "Platform"}},
		now:        time.Now,
	}
}

// WithTenantID sets the TenantId dimension property.
func (m *AgentMetrics) WithTenantID(id string) *AgentMetrics {
	m.properties["TenantId"] = id
	return m
}

// WithPlatform sets the Platform dimension property.
func (m *AgentMetrics) WithPlatform(p string) *AgentMetrics {
	m.properties["Platform"] = p
	return m
}

// PutProperty sets an arbitrary property on the EMF document.
func (m *AgentMetrics) PutProperty(key string, value any) *AgentMetrics {
	m.properties[key] = value
	return m
}

// PutMetric adds a metric with its value and unit.
func (m *AgentMetrics) PutMetric(name string, value float64, unit string) *AgentMetrics {
	m.metrics = append(m.metrics, metricDef{Name: name, Unit: unit})
	m.properties[name] = value
	return m
}

// RecordTurns records the number of agent turns.
func (m *AgentMetrics) RecordTurns(count int) *AgentMetrics {
	return m.PutMetric("agent.turns", float64(count), "Count")
}

// RecordTokens records the number of tokens used.
func (m *AgentMetrics) RecordTokens(count int) *AgentMetrics {
	return m.PutMetric("agent.tokens", float64(count), "Count")
}

// RecordTools records the number of tool invocations.
func (m *AgentMetrics) RecordTools(count int) *AgentMetrics {
	return m.PutMetric("agent.tools", float64(count), "Count")
}

// RecordLatency records the operation latency in milliseconds.
func (m *AgentMetrics) RecordLatency(ms int64) *AgentMetrics {
	return m.PutMetric("agent.latency", float64(ms), "Milliseconds")
}

// RecordErrors records the error count.
func (m *AgentMetrics) RecordErrors(count int) *AgentMetrics {
	return m.PutMetric("agent.errors", float64(count), "Count")
}

// Flush builds the CloudWatch EMF JSON document and prints it to stdout.
func (m *AgentMetrics) Flush() {
	doc := m.buildEMFDocument()
	data, err := json.Marshal(doc)
	if err != nil {
		return
	}
	fmt.Println(string(data))
}

// buildEMFDocument constructs the EMF JSON structure.
func (m *AgentMetrics) buildEMFDocument() map[string]any {
	doc := make(map[string]any)

	// Copy all properties (including dimension values and metric values)
	for k, v := range m.properties {
		doc[k] = v
	}

	// Build the _aws metadata
	cwMetrics := []map[string]any{
		{
			"Namespace":  "AiAgent",
			"Dimensions": m.dimensions,
			"Metrics":    m.metrics,
		},
	}

	doc["_aws"] = map[string]any{
		"Timestamp":         m.now().UnixMilli(),
		"CloudWatchMetrics": cwMetrics,
	}

	return doc
}
