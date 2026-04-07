package security

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func computeHMAC(body []byte, secret string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

func TestVerifyGitHubSignature_Valid(t *testing.T) {
	body := []byte(`{"action":"opened"}`)
	secret := "test-secret"
	sig := "sha256=" + computeHMAC(body, secret)

	err := VerifyGitHubSignature(map[string]string{"x-hub-signature-256": sig}, body, secret)
	assert.NoError(t, err)
}

func TestVerifyGitHubSignature_CaseInsensitiveHeader(t *testing.T) {
	body := []byte(`{"action":"opened"}`)
	secret := "test-secret"
	sig := "sha256=" + computeHMAC(body, secret)

	err := VerifyGitHubSignature(map[string]string{"X-Hub-Signature-256": sig}, body, secret)
	assert.NoError(t, err)
}

func TestVerifyGitHubSignature_Missing(t *testing.T) {
	err := VerifyGitHubSignature(map[string]string{}, []byte("body"), "secret")
	assert.ErrorIs(t, err, ErrMissingSignature)
}

func TestVerifyGitHubSignature_Invalid(t *testing.T) {
	headers := map[string]string{"x-hub-signature-256": "sha256=deadbeef"}
	err := VerifyGitHubSignature(headers, []byte("body"), "secret")
	assert.ErrorIs(t, err, ErrInvalidSignature)
}

func TestVerifyJiraWebhookToken_ValidHeader(t *testing.T) {
	headers := map[string]string{"x-jira-webhook-token": "my-token"}
	err := VerifyJiraWebhookToken(headers, "my-token")
	assert.NoError(t, err)
}

func TestVerifyJiraWebhookToken_ValidBearer(t *testing.T) {
	headers := map[string]string{"Authorization": "Bearer my-token"}
	err := VerifyJiraWebhookToken(headers, "my-token")
	assert.NoError(t, err)
}

func TestVerifyJiraWebhookToken_Missing(t *testing.T) {
	err := VerifyJiraWebhookToken(map[string]string{}, "token")
	assert.ErrorIs(t, err, ErrMissingToken)
}

func TestVerifyJiraWebhookToken_Invalid(t *testing.T) {
	headers := map[string]string{"x-jira-webhook-token": "wrong-token"}
	err := VerifyJiraWebhookToken(headers, "correct-token")
	assert.ErrorIs(t, err, ErrInvalidToken)
}

func TestVerifyBitbucketSignature_Valid(t *testing.T) {
	body := []byte(`{"push":"event"}`)
	secret := "bb-secret"
	sig := "sha256=" + computeHMAC(body, secret)

	err := VerifyBitbucketSignature(map[string]string{"x-hub-signature": sig}, body, secret)
	assert.NoError(t, err)
}

func TestVerifyBitbucketSignature_Missing(t *testing.T) {
	err := VerifyBitbucketSignature(map[string]string{}, []byte("body"), "secret")
	assert.ErrorIs(t, err, ErrMissingSignature)
}

func TestVerifyBitbucketSignature_Invalid(t *testing.T) {
	headers := map[string]string{"x-hub-signature": "sha256=invalid"}
	err := VerifyBitbucketSignature(headers, []byte("body"), "secret")
	assert.ErrorIs(t, err, ErrInvalidSignature)
}

func TestValidateRepoIdentifier(t *testing.T) {
	assert.NoError(t, ValidateRepoIdentifier("owner/repo"))
	assert.NoError(t, ValidateRepoIdentifier("my-org/my.repo_v2"))
	assert.Error(t, ValidateRepoIdentifier("invalid"))
	assert.Error(t, ValidateRepoIdentifier(""))
	assert.Error(t, ValidateRepoIdentifier("owner/repo/extra"))
	assert.Error(t, ValidateRepoIdentifier("owner repo"))
}

func TestValidateNumericID(t *testing.T) {
	assert.NoError(t, ValidateNumericID("12345"))
	assert.NoError(t, ValidateNumericID("0"))
	assert.Error(t, ValidateNumericID("abc"))
	assert.Error(t, ValidateNumericID(""))
	assert.Error(t, ValidateNumericID("12.34"))
}

func TestValidateTicketKey(t *testing.T) {
	assert.NoError(t, ValidateTicketKey("PROJ-123"))
	assert.NoError(t, ValidateTicketKey("AB2-1"))
	require.Error(t, ValidateTicketKey("proj-123"))
	require.Error(t, ValidateTicketKey("PROJ"))
	require.Error(t, ValidateTicketKey(""))
	require.Error(t, ValidateTicketKey("-123"))
}

func TestSanitizeCommentBody(t *testing.T) {
	assert.Equal(t, "Hello world", SanitizeCommentBody("<p>Hello <b>world</b></p>"))
	assert.Equal(t, "plain text", SanitizeCommentBody("plain text"))
	assert.Equal(t, "alert('xss')", SanitizeCommentBody("<script>alert('xss')</script>"))
}

func TestSanitizeAuthor(t *testing.T) {
	assert.Equal(t, "John Doe", SanitizeAuthor("John Doe"))
	assert.Equal(t, "user-name", SanitizeAuthor("user-name"))
	assert.Equal(t, "user.name@domain", SanitizeAuthor("user.name@domain"))
	assert.Equal(t, "cleanscript", SanitizeAuthor("clean<script>"))
}
