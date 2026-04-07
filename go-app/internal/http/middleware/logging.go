package middleware

import (
	"time"

	"github.com/labstack/echo/v4"
	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
)

// RequestLogger returns Echo middleware that logs each request with zerolog.
func RequestLogger() echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			start := time.Now()
			requestID := c.Response().Header().Get(echo.HeaderXRequestID)
			if requestID == "" {
				requestID = c.Request().Header.Get(echo.HeaderXRequestID)
			}

			logger := log.With().
				Str("request_id", requestID).
				Str("method", c.Request().Method).
				Str("path", c.Request().URL.Path).
				Logger()

			c.Set("logger", &logger)

			err := next(c)

			logger.Info().
				Int("status", c.Response().Status).
				Dur("latency", time.Since(start)).
				Msg("request completed")

			return err
		}
	}
}

// Logger extracts the zerolog logger from the echo context.
func Logger(c echo.Context) *zerolog.Logger {
	if l, ok := c.Get("logger").(*zerolog.Logger); ok {
		return l
	}
	l := log.Logger
	return &l
}
