package security

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"strings"
)

var (
	ErrMissingSignature = errors.New("missing signature header")
	ErrInvalidSignature = errors.New("invalid webhook signature")
	ErrMissingToken     = errors.New("missing webhook token")
	ErrInvalidToken     = errors.New("invalid webhook token")
)

// repoIdentifierPattern matches owner/repo format with alphanumeric, hyphens, underscores, and dots.
var repoIdentifierPattern = regexp.MustCompile(`^[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+$`)

// numericIDPattern matches purely numeric strings.
var numericIDPattern = regexp.MustCompile(`^\d+$`)

// ticketKeyPattern matches Jira-style ticket keys like PROJ-123.
var ticketKeyPattern = regexp.MustCompile(`^[A-Z][A-Z0-9]+-\d+$`)

// htmlTagPattern matches HTML tags for stripping.
var htmlTagPattern = regexp.MustCompile(`<[^>]*>`)

// authorSafePattern matches safe author name characters.
var authorSafePattern = regexp.MustCompile(`[^a-zA-Z0-9._@\- ]`)

// VerifyGitHubSignature verifies a GitHub webhook HMAC-SHA256 signature.
// The expected header is "x-hub-signature-256" with format "sha256=<hex>".
func VerifyGitHubSignature(headers map[string]string, body []byte, secret string) error {
	sig := headerValue(headers, "x-hub-signature-256")
	if sig == "" {
		return ErrMissingSignature
	}

	sig = strings.TrimPrefix(sig, "sha256=")
	return verifyHMACSHA256(body, secret, sig)
}

// VerifyJiraWebhookToken verifies a Jira webhook token from the
// "x-jira-webhook-token" header or "Authorization: Bearer <token>" header.
func VerifyJiraWebhookToken(headers map[string]string, expectedToken string) error {
	token := headerValue(headers, "x-jira-webhook-token")
	if token == "" {
		auth := headerValue(headers, "authorization")
		if strings.HasPrefix(auth, "Bearer ") {
			token = strings.TrimPrefix(auth, "Bearer ")
		}
	}

	if token == "" {
		return ErrMissingToken
	}

	if subtle.ConstantTimeCompare([]byte(token), []byte(expectedToken)) != 1 {
		return ErrInvalidToken
	}
	return nil
}

// VerifyBitbucketSignature verifies a Bitbucket webhook HMAC-SHA256 signature.
// The expected header is "x-hub-signature" with format "sha256=<hex>".
func VerifyBitbucketSignature(headers map[string]string, body []byte, secret string) error {
	sig := headerValue(headers, "x-hub-signature")
	if sig == "" {
		return ErrMissingSignature
	}

	sig = strings.TrimPrefix(sig, "sha256=")
	return verifyHMACSHA256(body, secret, sig)
}

// ValidateRepoIdentifier checks that the input is a valid owner/repo identifier.
func ValidateRepoIdentifier(repo string) error {
	if !repoIdentifierPattern.MatchString(repo) {
		return fmt.Errorf("%w: invalid repository identifier: %s", ErrInvalidToken, repo)
	}
	return nil
}

// ValidateNumericID checks that the input is a valid numeric ID string.
func ValidateNumericID(id string) error {
	if !numericIDPattern.MatchString(id) {
		return fmt.Errorf("%w: invalid numeric ID: %s", ErrInvalidToken, id)
	}
	return nil
}

// ValidateTicketKey checks that the input is a valid Jira-style ticket key (e.g. PROJ-123).
func ValidateTicketKey(key string) error {
	if !ticketKeyPattern.MatchString(key) {
		return fmt.Errorf("%w: invalid ticket key: %s", ErrInvalidToken, key)
	}
	return nil
}

// SanitizeCommentBody strips HTML tags from a comment body.
func SanitizeCommentBody(body string) string {
	return htmlTagPattern.ReplaceAllString(body, "")
}

// SanitizeAuthor removes unsafe characters from an author name.
func SanitizeAuthor(author string) string {
	return authorSafePattern.ReplaceAllString(author, "")
}

// headerValue performs a case-insensitive lookup of a header value.
func headerValue(headers map[string]string, key string) string {
	lower := strings.ToLower(key)
	for k, v := range headers {
		if strings.EqualFold(k, lower) {
			return v
		}
	}
	return ""
}

// verifyHMACSHA256 computes HMAC-SHA256 and compares against the expected hex signature.
func verifyHMACSHA256(body []byte, secret, expectedHex string) error {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	computed := hex.EncodeToString(mac.Sum(nil))

	if subtle.ConstantTimeCompare([]byte(computed), []byte(expectedHex)) != 1 {
		return ErrInvalidSignature
	}
	return nil
}
