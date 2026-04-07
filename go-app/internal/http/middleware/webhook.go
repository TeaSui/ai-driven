package middleware

import (
	"bytes"
	"io"
	"net/http"

	"github.com/labstack/echo/v4"

	"github.com/AirdropToTheMoon/ai-driven/internal/security"
)

// WebhookConfig configures webhook validation middleware.
type WebhookConfig struct {
	GitHubSecret    func() string
	JiraToken       func() string
	BitbucketSecret func() string
}

var errInvalidSig = map[string]string{"error": "invalid signature"}

func readBody(c echo.Context) ([]byte, error) {
	body, err := io.ReadAll(c.Request().Body)
	if err != nil {
		return nil, err
	}
	c.Request().Body = io.NopCloser(bytes.NewReader(body))
	c.Set("rawBody", body)
	return body, nil
}

func echoHeaders(r *http.Request) map[string]string {
	headers := make(map[string]string, len(r.Header))
	for k, v := range r.Header {
		if len(v) > 0 {
			headers[k] = v[0]
		}
	}
	return headers
}

// GitHubWebhook returns Echo middleware that validates GitHub HMAC signatures.
func GitHubWebhook(secretFn func() string) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			body, err := readBody(c)
			if err != nil {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "failed to read body"})
			}

			if err := security.VerifyGitHubSignature(echoHeaders(c.Request()), body, secretFn()); err != nil {
				return c.JSON(http.StatusUnauthorized, errInvalidSig)
			}

			return next(c)
		}
	}
}

// JiraWebhook returns Echo middleware that validates Jira webhook tokens.
func JiraWebhook(tokenFn func() string) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			if _, err := readBody(c); err != nil {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "failed to read body"})
			}

			if err := security.VerifyJiraWebhookToken(echoHeaders(c.Request()), tokenFn()); err != nil {
				return c.JSON(http.StatusUnauthorized, errInvalidSig)
			}

			return next(c)
		}
	}
}

// BitbucketWebhook returns Echo middleware that validates Bitbucket HMAC signatures.
func BitbucketWebhook(secretFn func() string) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			body, err := readBody(c)
			if err != nil {
				return c.JSON(http.StatusBadRequest, map[string]string{"error": "failed to read body"})
			}

			if err := security.VerifyBitbucketSignature(echoHeaders(c.Request()), body, secretFn()); err != nil {
				return c.JSON(http.StatusUnauthorized, errInvalidSig)
			}

			return next(c)
		}
	}
}
