package security

import (
	"regexp"
	"strings"
)

// MaxInputLength is the maximum allowed input length in characters.
const MaxInputLength = 100_000

// injectionPatterns are patterns that indicate prompt injection attempts.
var injectionPatterns = []string{
	"ignore previous instructions",
	"ignore all previous instructions",
	"disregard previous instructions",
	"forget previous instructions",
	"you are now",
	"<system>",
	"system:",
}

// htmlStripPattern matches HTML tags for removal during sanitization.
var htmlStripPattern = regexp.MustCompile(`<[^>]*>`)

// Sanitize cleans user input by truncating to MaxInputLength,
// stripping HTML tags, and neutralizing prompt injection patterns.
func Sanitize(input string) string {
	if len(input) > MaxInputLength {
		input = input[:MaxInputLength]
	}

	// Neutralize injection patterns before stripping HTML
	// (so patterns like <system> are caught before tag removal)
	lower := strings.ToLower(input)
	for _, pattern := range injectionPatterns {
		idx := strings.Index(lower, pattern)
		for idx >= 0 {
			end := idx + len(pattern)
			original := input[idx:end]
			replacement := "[blocked: " + original + "]"
			input = input[:idx] + replacement + input[end:]
			// Recompute lower for next search
			lower = strings.ToLower(input)
			// Continue searching after the replacement
			idx = strings.Index(lower[idx+len(replacement):], pattern)
			if idx >= 0 {
				idx += (end - len(pattern)) + len(replacement)
			}
		}
	}

	// Strip HTML tags after injection neutralization
	input = htmlStripPattern.ReplaceAllString(input, "")

	return input
}
