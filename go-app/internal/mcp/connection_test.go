package mcp

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestServerConfig_Validate(t *testing.T) {
	tests := []struct {
		name    string
		config  ServerConfig
		wantErr string
	}{
		{
			name:    "empty namespace",
			config:  ServerConfig{Transport: "stdio"},
			wantErr: "namespace is required",
		},
		{
			name:    "empty transport",
			config:  ServerConfig{Namespace: "test"},
			wantErr: "transport is required",
		},
		{
			name:    "stdio without command",
			config:  ServerConfig{Namespace: "test", Transport: "stdio"},
			wantErr: "command required",
		},
		{
			name:    "http without url",
			config:  ServerConfig{Namespace: "test", Transport: "http"},
			wantErr: "url required",
		},
		{
			name:    "unsupported transport",
			config:  ServerConfig{Namespace: "test", Transport: "grpc"},
			wantErr: "unsupported transport",
		},
		{
			name:   "valid stdio",
			config: ServerConfig{Namespace: "test", Transport: "stdio", Command: "/usr/bin/node"},
		},
		{
			name:   "valid http",
			config: ServerConfig{Namespace: "test", Transport: "http", URL: "http://localhost:8080"},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.config.Validate()
			if tt.wantErr != "" {
				require.Error(t, err)
				assert.Contains(t, err.Error(), tt.wantErr)
			} else {
				require.NoError(t, err)
			}
		})
	}
}

func TestBuildEnv(t *testing.T) {
	t.Run("nil map", func(t *testing.T) {
		assert.Nil(t, buildEnv(nil))
	})

	t.Run("empty map", func(t *testing.T) {
		assert.Nil(t, buildEnv(map[string]string{}))
	})

	t.Run("populated map", func(t *testing.T) {
		env := buildEnv(map[string]string{"KEY": "VALUE", "FOO": "BAR"})
		assert.Len(t, env, 2)
		assert.Contains(t, env, "KEY=VALUE")
		assert.Contains(t, env, "FOO=BAR")
	})
}
