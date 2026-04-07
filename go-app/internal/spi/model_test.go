package spi

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// --- BranchName tests ---

func TestNewBranchName_Valid(t *testing.T) {
	tests := []struct {
		name  string
		input string
	}{
		{"simple", "main"},
		{"feature branch", "feature/my-feature"},
		{"with dots", "release/1.0.0"},
		{"with numbers", "hotfix/123"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			bn, err := NewBranchName(tt.input)
			require.NoError(t, err)
			assert.Equal(t, tt.input, bn.Value())
			assert.Equal(t, tt.input, bn.String())
		})
	}
}

func TestNewBranchName_Invalid(t *testing.T) {
	tests := []struct {
		name  string
		input string
	}{
		{"empty", ""},
		{"whitespace only", "   "},
		{"reserved HEAD", "HEAD"},
		{"reserved FETCH_HEAD", "FETCH_HEAD"},
		{"reserved MERGE_HEAD", "MERGE_HEAD"},
		{"contains ..", "feature/../main"},
		{"contains @{", "feature@{0}"},
		{"contains newline", "feature\nbranch"},
		{"starts with /", "/feature"},
		{"ends with /", "feature/"},
		{"too long", string(make([]byte, 256))},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := NewBranchName(tt.input)
			require.Error(t, err)
			var ve *ValidationError
			assert.ErrorAs(t, err, &ve)
		})
	}
}

func TestBranchName_IsMainBranch(t *testing.T) {
	main, _ := NewBranchName("main")
	master, _ := NewBranchName("master")
	feature, _ := NewBranchName("feature/x")

	assert.True(t, main.IsMainBranch())
	assert.True(t, master.IsMainBranch())
	assert.False(t, feature.IsMainBranch())
}

func TestNewBranchNameOrNil(t *testing.T) {
	assert.NotNil(t, NewBranchNameOrNil("main"))
	assert.Nil(t, NewBranchNameOrNil(""))
	assert.Nil(t, NewBranchNameOrNil("HEAD"))
}

// --- TicketKey tests ---

func TestNewTicketKey_Valid(t *testing.T) {
	tests := []struct {
		name      string
		input     string
		project   string
		ticketNum string
	}{
		{"standard", "PROJ-123", "PROJ", "123"},
		{"short project", "AB-1", "AB", "1"},
		{"long project", "MYPROJECT-99999", "MYPROJECT", "99999"},
		{"with digits", "A1B-42", "A1B", "42"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tk, err := NewTicketKey(tt.input)
			require.NoError(t, err)
			assert.Equal(t, tt.input, tk.Value())
			assert.Equal(t, tt.project, tk.ProjectKey())
			assert.Equal(t, tt.ticketNum, tk.TicketNumber())
		})
	}
}

func TestNewTicketKey_Invalid(t *testing.T) {
	tests := []struct {
		name  string
		input string
	}{
		{"empty", ""},
		{"too short", "A-"},
		{"lowercase", "proj-123"},
		{"no dash", "PROJ123"},
		{"starts with number", "1PROJ-123"},
		{"no number", "PROJ-"},
		{"special chars", "PROJ-12#3"},
		{"too long", "ABCDEFGHIJKLMNOPQRSTUVWXYZABCD-12"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := NewTicketKey(tt.input)
			require.Error(t, err)
			var ve *ValidationError
			assert.ErrorAs(t, err, &ve)
		})
	}
}

func TestNewTicketKeyOrNil(t *testing.T) {
	assert.NotNil(t, NewTicketKeyOrNil("PROJ-123"))
	assert.Nil(t, NewTicketKeyOrNil(""))
	assert.Nil(t, NewTicketKeyOrNil("invalid"))
}

// --- OperationContext tests ---

func TestNewOperationContext(t *testing.T) {
	ctx := NewOperationContext("tenant-1")

	assert.Equal(t, "tenant-1", ctx.TenantID)
	assert.NotEmpty(t, ctx.CorrelationID)
	assert.False(t, ctx.Timestamp.IsZero())
	assert.Empty(t, ctx.ProjectKey())
}

func TestOperationContext_ProjectKey(t *testing.T) {
	tk, _ := NewTicketKey("PROJ-42")
	ctx := NewOperationContext("tenant-1")
	ctx.TicketKey = &tk

	assert.Equal(t, "PROJ", ctx.ProjectKey())
}
