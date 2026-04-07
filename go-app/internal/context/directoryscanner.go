package context

import (
	"path/filepath"
	"strings"
)

// Component category constants used as map keys in scan results.
const (
	CategoryFrontend       = "frontend"
	CategoryBackend        = "backend"
	CategoryInfrastructure = "infrastructure"
	CategoryTest           = "test"
	CategoryConfig         = "config"
	CategoryDocs           = "docs"
)

// directoryPatterns maps each category to directory name patterns.
var directoryPatterns = map[string][]string{
	CategoryFrontend:       {"ui", "web", "static", "public", "assets", "views", "templates", "js", "ts", "css"},
	CategoryBackend:        {"application", "core", "service", "controller", "model", "src/main/java", "app", "api"},
	CategoryInfrastructure: {"infrastructure", "infra", "cdk", "terraform", "k8s", "docker", "cloudformation"},
	CategoryTest:           {"test", "tests", "spec", "e2e", "it", "junit"},
	CategoryConfig:         {"config", "configuration", "conf", "settings", ".github", ".circleci"},
	CategoryDocs:           {"docs", "doc", "documentation", "wiki"},
}

// DirectoryScanner categorizes repository directory paths into component categories
// (frontend, backend, infrastructure, test, config, docs) based on directory name patterns.
type DirectoryScanner struct{}

// NewDirectoryScanner creates a new DirectoryScanner.
func NewDirectoryScanner() *DirectoryScanner {
	return &DirectoryScanner{}
}

// Scan takes a file tree (list of file/directory paths) and returns a map
// of component categories to matching directory paths.
func (d *DirectoryScanner) Scan(fileTree []string) map[string][]string {
	result := make(map[string][]string)
	seen := make(map[string]map[string]bool)

	for cat := range directoryPatterns {
		result[cat] = []string{}
		seen[cat] = make(map[string]bool)
	}

	for _, path := range fileTree {
		if path == "" {
			continue
		}
		// Normalize: remove trailing slash, use forward slashes
		normalized := strings.TrimSuffix(strings.ReplaceAll(path, "\\", "/"), "/")
		parts := strings.Split(normalized, "/")

		for cat, patterns := range directoryPatterns {
			if matchesCategory(normalized, parts, patterns) {
				// Use the deepest matching directory segment for the path.
				// For multi-segment patterns like "src/main/java", use the full match context.
				dirPath := findMatchingDir(normalized, parts, patterns)
				if dirPath != "" && !seen[cat][dirPath] {
					seen[cat][dirPath] = true
					result[cat] = append(result[cat], dirPath)
				}
			}
		}
	}

	return result
}

// matchesCategory checks if any path segment matches any of the category's patterns.
func matchesCategory(fullPath string, parts []string, patterns []string) bool {
	lower := strings.ToLower(fullPath)
	for _, pattern := range patterns {
		if strings.Contains(pattern, "/") {
			// Multi-segment pattern: check if full path contains the pattern
			if strings.Contains(lower, strings.ToLower(pattern)) {
				return true
			}
		} else {
			for _, part := range parts {
				if strings.EqualFold(part, pattern) {
					return true
				}
			}
		}
	}
	return false
}

// findMatchingDir returns the directory path up to and including the matching segment.
func findMatchingDir(fullPath string, parts []string, patterns []string) string {
	lower := strings.ToLower(fullPath)
	for _, pattern := range patterns {
		if strings.Contains(pattern, "/") {
			// Multi-segment pattern
			patLower := strings.ToLower(pattern)
			idx := strings.Index(lower, patLower)
			if idx >= 0 {
				end := idx + len(pattern)
				return fullPath[:end]
			}
		} else {
			for i, part := range parts {
				if strings.EqualFold(part, pattern) {
					return filepath.Join(parts[:i+1]...)
				}
			}
		}
	}
	return ""
}
