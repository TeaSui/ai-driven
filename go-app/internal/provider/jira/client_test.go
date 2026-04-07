package jira

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newTestClient(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return &Client{
		baseURL:    server.URL,
		authHeader: "Basic dGVzdDp0b2tlbg==",
		httpClient: server.Client(),
	}
}

func TestClient_Name(t *testing.T) {
	c := NewClient("https://jira.example.com", "user@test.com", "token")
	assert.Equal(t, "jira", c.Name())
}

func TestClient_GetTicketDetails(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/rest/api/3/issue/PROJ-123", r.URL.Path)
		assert.Contains(t, r.Header.Get("Authorization"), "Basic ")
		json.NewEncoder(w).Encode(map[string]any{
			"id":  "12345",
			"key": "PROJ-123",
			"fields": map[string]any{
				"summary": "Test ticket",
				"status":  map[string]string{"name": "Open"},
			},
		})
	})

	result, err := client.GetTicketDetails(t.Context(), nil, "PROJ-123")
	require.NoError(t, err)
	assert.Equal(t, "PROJ-123", result["key"])
	fields := result["fields"].(map[string]any)
	assert.Equal(t, "Test ticket", fields["summary"])
}

func TestClient_PostComment(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/rest/api/3/issue/PROJ-123/comment", r.URL.Path)

		var body map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		adfBody := body["body"].(map[string]any)
		assert.Equal(t, "doc", adfBody["type"])

		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]string{"id": "comment-1"})
	})

	err := client.PostComment(t.Context(), nil, "PROJ-123", "Hello from agent")
	require.NoError(t, err)
}

func TestClient_UpdateLabels(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		assert.Equal(t, "/rest/api/3/issue/PROJ-123", r.URL.Path)

		var body map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		update := body["update"].(map[string]any)
		labels := update["labels"].([]any)
		assert.Len(t, labels, 3)

		w.WriteHeader(http.StatusNoContent)
	})

	err := client.UpdateLabels(t.Context(), nil, "PROJ-123",
		[]string{"bug", "critical"}, []string{"needs-triage"})
	require.NoError(t, err)
}

func TestClient_UpdateStatus(t *testing.T) {
	calls := 0
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Method == http.MethodGet {
			json.NewEncoder(w).Encode(map[string]any{
				"transitions": []map[string]any{
					{"id": "11", "to": map[string]string{"name": "In Progress"}},
					{"id": "21", "to": map[string]string{"name": "Done"}},
				},
			})
			return
		}
		assert.Equal(t, http.MethodPost, r.Method)
		var body map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		tr := body["transition"].(map[string]any)
		assert.Equal(t, "21", tr["id"])
		w.WriteHeader(http.StatusNoContent)
	})

	err := client.UpdateStatus(t.Context(), nil, "PROJ-123", "Done")
	require.NoError(t, err)
	assert.Equal(t, 2, calls)
}

func TestClient_UpdateStatus_NotFound(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, _ *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"transitions": []map[string]any{
				{"id": "11", "to": map[string]string{"name": "In Progress"}},
			},
		})
	})

	err := client.UpdateStatus(t.Context(), nil, "PROJ-123", "Rejected")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "not available")
}

func TestClient_GetTicketDetails_Error(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte("not found"))
	})

	_, err := client.GetTicketDetails(t.Context(), nil, "PROJ-999")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "404")
}

func TestBuildADFComment(t *testing.T) {
	result := buildADFComment("Hello world")
	body := result["body"].(map[string]any)
	assert.Equal(t, "doc", body["type"])
	assert.Equal(t, 1, body["version"])
	content := body["content"].([]map[string]any)
	assert.Len(t, content, 1)
	assert.Equal(t, "paragraph", content[0]["type"])
}
