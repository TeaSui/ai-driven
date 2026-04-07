package provider

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

// CheckResponse validates an HTTP response status code and returns an appropriate error.
func CheckResponse(resp *http.Response, body []byte) error {
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return nil
	}
	return &HTTPError{
		StatusCode: resp.StatusCode,
		Body:       string(body),
	}
}

// HTTPError represents an HTTP error from a provider API.
type HTTPError struct {
	StatusCode int
	Body       string
}

func (e *HTTPError) Error() string {
	return fmt.Sprintf("HTTP %d: %s", e.StatusCode, e.Body)
}

// IsNotFound returns true if the error is a 404.
func (e *HTTPError) IsNotFound() bool {
	return e.StatusCode == 404
}

// IsRateLimit returns true if the error is a 429.
func (e *HTTPError) IsRateLimit() bool {
	return e.StatusCode == 429
}

// DoJSON executes an HTTP request and decodes the response JSON into target.
// Returns the raw body bytes and any error.
func DoJSON(client *http.Client, req *http.Request, target any) ([]byte, error) {
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("execute request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}

	if err := CheckResponse(resp, body); err != nil {
		return body, err
	}

	if target != nil && len(body) > 0 {
		if err := json.Unmarshal(body, target); err != nil {
			return body, fmt.Errorf("decode response: %w", err)
		}
	}

	return body, nil
}
