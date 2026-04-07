package model

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNewTokenUsage(t *testing.T) {
	tu := NewTokenUsage(100, 50)
	assert.Equal(t, 100, tu.InputTokens)
	assert.Equal(t, 50, tu.OutputTokens)
	assert.Equal(t, 150, tu.TotalTokens)
}

func TestTokenUsage_Add(t *testing.T) {
	tu := NewTokenUsage(100, 50)
	tu.Add(NewTokenUsage(200, 75))

	assert.Equal(t, 300, tu.InputTokens)
	assert.Equal(t, 125, tu.OutputTokens)
	assert.Equal(t, 425, tu.TotalTokens)
}
