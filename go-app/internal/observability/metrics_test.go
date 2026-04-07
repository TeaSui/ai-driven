package observability

import (
	"encoding/json"
	"io"
	"os"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestMetricRecordingAndProperties(t *testing.T) {
	m := NewAgentMetrics().
		WithTenantID("tenant-1").
		WithPlatform("jira").
		PutProperty("custom", "value").
		RecordTurns(5).
		RecordTokens(1000).
		RecordTools(3).
		RecordLatency(250).
		RecordErrors(1)

	doc := m.buildEMFDocument()

	assert.Equal(t, "tenant-1", doc["TenantId"])
	assert.Equal(t, "jira", doc["Platform"])
	assert.Equal(t, "value", doc["custom"])
	assert.Equal(t, float64(5), doc["agent.turns"])
	assert.Equal(t, float64(1000), doc["agent.tokens"])
	assert.Equal(t, float64(3), doc["agent.tools"])
	assert.Equal(t, float64(250), doc["agent.latency"])
	assert.Equal(t, float64(1), doc["agent.errors"])
}

func TestFlushOutputFormat(t *testing.T) {
	fixedTime := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)

	m := NewAgentMetrics()
	m.now = func() time.Time { return fixedTime }
	m.WithTenantID("tenant-1").
		WithPlatform("jira").
		RecordTurns(5)

	// Capture stdout
	old := os.Stdout
	r, w, err := os.Pipe()
	require.NoError(t, err)
	os.Stdout = w

	m.Flush()

	w.Close()
	os.Stdout = old

	output, err := io.ReadAll(r)
	require.NoError(t, err)

	var doc map[string]any
	err = json.Unmarshal(output, &doc)
	require.NoError(t, err, "output should be valid JSON: %s", string(output))

	// Verify top-level properties
	assert.Equal(t, "tenant-1", doc["TenantId"])
	assert.Equal(t, "jira", doc["Platform"])
	assert.Equal(t, float64(5), doc["agent.turns"])

	// Verify _aws metadata
	awsMeta, ok := doc["_aws"].(map[string]any)
	require.True(t, ok, "_aws should be a map")

	assert.Equal(t, float64(fixedTime.UnixMilli()), awsMeta["Timestamp"])

	cwMetrics, ok := awsMeta["CloudWatchMetrics"].([]any)
	require.True(t, ok, "CloudWatchMetrics should be an array")
	require.Len(t, cwMetrics, 1)

	entry, ok := cwMetrics[0].(map[string]any)
	require.True(t, ok)
	assert.Equal(t, "AiAgent", entry["Namespace"])

	dims, ok := entry["Dimensions"].([]any)
	require.True(t, ok)
	require.Len(t, dims, 1)
	dimArr, ok := dims[0].([]any)
	require.True(t, ok)
	assert.Contains(t, dimArr, "TenantId")
	assert.Contains(t, dimArr, "Platform")

	metrics, ok := entry["Metrics"].([]any)
	require.True(t, ok)
	require.Len(t, metrics, 1)
	metricEntry, ok := metrics[0].(map[string]any)
	require.True(t, ok)
	assert.Equal(t, "agent.turns", metricEntry["Name"])
	assert.Equal(t, "Count", metricEntry["Unit"])
}

func TestPutMetricCustom(t *testing.T) {
	m := NewAgentMetrics().
		PutMetric("custom.metric", 99.5, "Percent")

	doc := m.buildEMFDocument()
	assert.Equal(t, 99.5, doc["custom.metric"])
	assert.Len(t, m.metrics, 1)
	assert.Equal(t, "custom.metric", m.metrics[0].Name)
	assert.Equal(t, "Percent", m.metrics[0].Unit)
}
