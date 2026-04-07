package middleware

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"time"

	"github.com/labstack/echo/v4"
	"github.com/rs/zerolog/log"
)

// ShadowProxy duplicates incoming webhook requests to a shadow target
// (the Java service) asynchronously. The primary response is always from
// the Go service; the shadow response is logged but not returned.
func ShadowProxy(shadowBaseURL string) echo.MiddlewareFunc {
	client := &http.Client{Timeout: 30 * time.Second}

	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			if shadowBaseURL == "" {
				return next(c)
			}

			// Read body for forwarding
			body, err := io.ReadAll(c.Request().Body)
			if err != nil {
				return next(c)
			}
			c.Request().Body = io.NopCloser(bytes.NewReader(body))

			// Fire-and-forget shadow request
			go func() {
				ctx := context.Background()
				shadowURL := shadowBaseURL + c.Request().URL.Path
				req, err := http.NewRequestWithContext(ctx, c.Request().Method, shadowURL, bytes.NewReader(body))
				if err != nil {
					log.Warn().Err(err).Str("url", shadowURL).Msg("shadow: failed to create request")
					return
				}

				// Copy relevant headers
				for _, h := range []string{"Content-Type", "X-Hub-Signature-256", "X-Jira-Webhook-Token"} {
					if v := c.Request().Header.Get(h); v != "" {
						req.Header.Set(h, v)
					}
				}

				resp, err := client.Do(req)
				if err != nil {
					log.Warn().Err(err).Str("url", shadowURL).Msg("shadow: request failed")
					return
				}
				defer resp.Body.Close()

				log.Info().
					Str("url", shadowURL).
					Int("status", resp.StatusCode).
					Msg("shadow: forwarded webhook")
			}()

			return next(c)
		}
	}
}
