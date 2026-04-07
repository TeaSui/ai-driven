package secrets

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/secretsmanager"
	"github.com/rs/zerolog/log"
)

// SecretsClient defines the subset of secretsmanager.Client used by Resolver.
type SecretsClient interface {
	GetSecretValue(ctx context.Context, params *secretsmanager.GetSecretValueInput, optFns ...func(*secretsmanager.Options)) (*secretsmanager.GetSecretValueOutput, error)
}

// Resolver fetches and caches secrets from AWS Secrets Manager.
type Resolver struct {
	client SecretsClient
	cache  sync.Map
}

// NewResolver creates a new secrets resolver.
func NewResolver(client SecretsClient) *Resolver {
	return &Resolver{client: client}
}

// JiraSecret holds parsed Jira credentials.
type JiraSecret struct {
	BaseURL  string `json:"baseUrl"`
	Email    string `json:"email"`
	APIToken string `json:"apiToken"`
}

// GitHubSecret holds parsed GitHub credentials.
type GitHubSecret struct {
	Owner string `json:"owner"`
	Repo  string `json:"repo"`
	Token string `json:"token"`
}

// BitbucketSecret holds parsed Bitbucket credentials.
type BitbucketSecret struct {
	Workspace   string `json:"workspace"`
	RepoSlug    string `json:"repoSlug"`
	Username    string `json:"username"`
	AppPassword string `json:"appPassword"`
}

// ResolveString fetches a plain string secret value. Returns empty string if ARN is empty.
func (r *Resolver) ResolveString(ctx context.Context, secretARN string) (string, error) {
	if secretARN == "" {
		return "", nil
	}

	if cached, ok := r.cache.Load(secretARN); ok {
		return cached.(string), nil
	}

	val, err := r.fetchSecret(ctx, secretARN)
	if err != nil {
		return "", err
	}

	r.cache.Store(secretARN, val)
	return val, nil
}

// ResolveJira fetches and parses Jira credentials from Secrets Manager.
func (r *Resolver) ResolveJira(ctx context.Context, secretARN string) (*JiraSecret, error) {
	if secretARN == "" {
		return nil, nil
	}

	raw, err := r.fetchSecret(ctx, secretARN)
	if err != nil {
		return nil, fmt.Errorf("resolve jira secret: %w", err)
	}

	var secret JiraSecret
	if err := json.Unmarshal([]byte(raw), &secret); err != nil {
		return nil, fmt.Errorf("parse jira secret JSON: %w", err)
	}
	return &secret, nil
}

// ResolveGitHub fetches and parses GitHub credentials from Secrets Manager.
func (r *Resolver) ResolveGitHub(ctx context.Context, secretARN string) (*GitHubSecret, error) {
	if secretARN == "" {
		return nil, nil
	}

	raw, err := r.fetchSecret(ctx, secretARN)
	if err != nil {
		return nil, fmt.Errorf("resolve github secret: %w", err)
	}

	var secret GitHubSecret
	if err := json.Unmarshal([]byte(raw), &secret); err != nil {
		return nil, fmt.Errorf("parse github secret JSON: %w", err)
	}
	return &secret, nil
}

// ResolveBitbucket fetches and parses Bitbucket credentials from Secrets Manager.
func (r *Resolver) ResolveBitbucket(ctx context.Context, secretARN string) (*BitbucketSecret, error) {
	if secretARN == "" {
		return nil, nil
	}

	raw, err := r.fetchSecret(ctx, secretARN)
	if err != nil {
		return nil, fmt.Errorf("resolve bitbucket secret: %w", err)
	}

	var secret BitbucketSecret
	if err := json.Unmarshal([]byte(raw), &secret); err != nil {
		return nil, fmt.Errorf("parse bitbucket secret JSON: %w", err)
	}
	return &secret, nil
}

func (r *Resolver) fetchSecret(ctx context.Context, secretARN string) (string, error) {
	if cached, ok := r.cache.Load(secretARN); ok {
		return cached.(string), nil
	}

	output, err := r.client.GetSecretValue(ctx, &secretsmanager.GetSecretValueInput{
		SecretId: aws.String(secretARN),
	})
	if err != nil {
		return "", fmt.Errorf("fetch secret %s: %w", secretARN, err)
	}

	val := aws.ToString(output.SecretString)
	r.cache.Store(secretARN, val)

	log.Debug().Str("secretARN", secretARN).Msg("resolved secret from Secrets Manager")
	return val, nil
}
