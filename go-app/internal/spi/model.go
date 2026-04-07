package spi

import (
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
)

// OperationContext carries security, multi-tenancy, and tracing context through all operations.
type OperationContext struct {
	TenantID      string     `json:"tenantId"`
	CorrelationID string     `json:"correlationId"`
	TicketKey     *TicketKey `json:"ticketKey,omitempty"`
	UserID        string     `json:"userId,omitempty"`
	RequestID     string     `json:"requestId,omitempty"`
	Timestamp     time.Time  `json:"timestamp"`
	Source        string     `json:"source,omitempty"`
}

func NewOperationContext(tenantID string) OperationContext {
	return OperationContext{
		TenantID:      tenantID,
		CorrelationID: uuid.New().String(),
		Timestamp:     time.Now(),
	}
}

func (o *OperationContext) ProjectKey() string {
	if o.TicketKey != nil {
		return o.TicketKey.ProjectKey()
	}
	return ""
}

func (o *OperationContext) String() string {
	return fmt.Sprintf("OperationContext{tenant=%s, correlation=%s, ticket=%v}",
		o.TenantID, o.CorrelationID, o.TicketKey)
}

// BranchName is an immutable domain value object for git branch names with validation.
type BranchName struct {
	value string
}

const branchNameMaxLen = 255

var (
	reservedBranchNames = map[string]bool{"HEAD": true, "FETCH_HEAD": true, "MERGE_HEAD": true}
	branchNameInvalid   = []string{"..", "@{", "\n", "\r"}
)

func NewBranchName(name string) (BranchName, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return BranchName{}, &ValidationError{Message: "branch name must not be empty"}
	}
	if len(name) > branchNameMaxLen {
		return BranchName{}, &ValidationError{Message: fmt.Sprintf("branch name must not exceed %d characters", branchNameMaxLen)}
	}
	if reservedBranchNames[name] {
		return BranchName{}, &ValidationError{Message: fmt.Sprintf("branch name '%s' is reserved", name)}
	}
	for _, s := range branchNameInvalid {
		if strings.Contains(name, s) {
			return BranchName{}, &ValidationError{Message: fmt.Sprintf("branch name must not contain '%s'", s)}
		}
	}
	if strings.HasPrefix(name, "/") || strings.HasSuffix(name, "/") {
		return BranchName{}, &ValidationError{Message: "branch name must not start or end with '/'"}
	}
	return BranchName{value: name}, nil
}

func NewBranchNameOrNil(name string) *BranchName {
	bn, err := NewBranchName(name)
	if err != nil {
		return nil
	}
	return &bn
}

func (b BranchName) Value() string  { return b.value }
func (b BranchName) String() string { return b.value }

func (b BranchName) IsMainBranch() bool {
	return b.value == "main" || b.value == "master"
}

// TicketKey is an immutable domain value object for issue tracker ticket keys (e.g., PROJ-123).
type TicketKey struct {
	value string
}

var ticketKeyPattern = regexp.MustCompile(`^[A-Z][A-Z0-9]*-\d+$`)

func NewTicketKey(key string) (TicketKey, error) {
	key = strings.TrimSpace(key)
	if len(key) < 3 || len(key) > 32 {
		return TicketKey{}, &ValidationError{Message: fmt.Sprintf("ticket key must be 3-32 characters, got %d", len(key))}
	}
	if !ticketKeyPattern.MatchString(key) {
		return TicketKey{}, &ValidationError{Message: fmt.Sprintf("ticket key '%s' does not match pattern PROJECT-123", key)}
	}
	return TicketKey{value: key}, nil
}

func NewTicketKeyOrNil(key string) *TicketKey {
	tk, err := NewTicketKey(key)
	if err != nil {
		return nil
	}
	return &tk
}

func (t TicketKey) Value() string  { return t.value }
func (t TicketKey) String() string { return t.value }

func (t TicketKey) ProjectKey() string {
	idx := strings.LastIndex(t.value, "-")
	if idx < 0 {
		return ""
	}
	return t.value[:idx]
}

func (t TicketKey) TicketNumber() string {
	idx := strings.LastIndex(t.value, "-")
	if idx < 0 {
		return ""
	}
	return t.value[idx+1:]
}

// ValidationError represents a domain validation failure.
type ValidationError struct {
	Message string
}

func (e *ValidationError) Error() string {
	return e.Message
}
