package middleware

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/labstack/echo/v4"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func computeHMAC(body []byte, secret string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	return "sha256=" + hex.EncodeToString(mac.Sum(nil))
}

func TestGitHubWebhook_ValidSignature(t *testing.T) {
	secret := "test-secret"
	body := []byte(`{"action":"created"}`)
	sig := computeHMAC(body, secret)

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(body))
	req.Header.Set("X-Hub-Signature-256", sig)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	handler := GitHubWebhook(func() string { return secret })(func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]string{"status": "ok"})
	})

	err := handler(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, rec.Code)

	rawBody, ok := c.Get("rawBody").([]byte)
	assert.True(t, ok)
	assert.Equal(t, body, rawBody)
}

func TestGitHubWebhook_InvalidSignature(t *testing.T) {
	body := []byte(`{"action":"created"}`)

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(body))
	req.Header.Set("X-Hub-Signature-256", "sha256=invalid")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	handler := GitHubWebhook(func() string { return "test-secret" })(func(c echo.Context) error {
		return c.JSON(http.StatusOK, nil)
	})

	err := handler(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
}

func TestGitHubWebhook_MissingSignature(t *testing.T) {
	body := []byte(`{"action":"created"}`)

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	handler := GitHubWebhook(func() string { return "test-secret" })(func(c echo.Context) error {
		return c.JSON(http.StatusOK, nil)
	})

	err := handler(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
}

func TestJiraWebhook_ValidToken(t *testing.T) {
	token := "jira-secret-token"
	body := []byte(`{"webhookEvent":"jira:issue_updated"}`)

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(body))
	req.Header.Set("X-Jira-Webhook-Token", token)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	handler := JiraWebhook(func() string { return token })(func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]string{"status": "ok"})
	})

	err := handler(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestJiraWebhook_InvalidToken(t *testing.T) {
	body := []byte(`{"webhookEvent":"jira:issue_updated"}`)

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(body))
	req.Header.Set("X-Jira-Webhook-Token", "wrong-token")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	handler := JiraWebhook(func() string { return "correct-token" })(func(c echo.Context) error {
		return c.JSON(http.StatusOK, nil)
	})

	err := handler(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
}

func TestJiraWebhook_MissingToken(t *testing.T) {
	body := []byte(`{"webhookEvent":"jira:issue_updated"}`)

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	handler := JiraWebhook(func() string { return "some-token" })(func(c echo.Context) error {
		return c.JSON(http.StatusOK, nil)
	})

	err := handler(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
}

func TestBitbucketWebhook_ValidSignature(t *testing.T) {
	secret := "bb-secret"
	body := []byte(`{"event":"repo:push"}`)
	sig := computeHMAC(body, secret)

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(body))
	req.Header.Set("X-Hub-Signature", sig)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	handler := BitbucketWebhook(func() string { return secret })(func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]string{"status": "ok"})
	})

	err := handler(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestBitbucketWebhook_InvalidSignature(t *testing.T) {
	body := []byte(`{"event":"repo:push"}`)

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(body))
	req.Header.Set("X-Hub-Signature", "sha256=wrong")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	handler := BitbucketWebhook(func() string { return "bb-secret" })(func(c echo.Context) error {
		return c.JSON(http.StatusOK, nil)
	})

	err := handler(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
}

func TestBitbucketWebhook_MissingSignature(t *testing.T) {
	body := []byte(`{"event":"repo:push"}`)

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	handler := BitbucketWebhook(func() string { return "bb-secret" })(func(c echo.Context) error {
		return c.JSON(http.StatusOK, nil)
	})

	err := handler(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
}

func TestWebhook_BodyRestoredForHandler(t *testing.T) {
	secret := "test-secret"
	body := []byte(`{"data":"test-payload"}`)
	sig := computeHMAC(body, secret)

	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(body))
	req.Header.Set("X-Hub-Signature-256", sig)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	var handlerBody []byte
	handler := GitHubWebhook(func() string { return secret })(func(c echo.Context) error {
		b := make([]byte, 1024)
		n, _ := c.Request().Body.Read(b)
		handlerBody = b[:n]
		return c.JSON(http.StatusOK, nil)
	})

	err := handler(c)
	require.NoError(t, err)
	assert.Equal(t, body, handlerBody)
}
