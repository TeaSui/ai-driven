package model

import (
	"fmt"
	"time"
)

// ConversationMessage is a persisted message in a DynamoDB conversation.
type ConversationMessage struct {
	PK          string    `dynamodbav:"PK" json:"pk"`
	SK          string    `dynamodbav:"SK" json:"sk"`
	Role        string    `dynamodbav:"role" json:"role"`
	Author      string    `dynamodbav:"author" json:"author"`
	ContentJSON string    `dynamodbav:"contentJson" json:"contentJson"`
	CommentID   string    `dynamodbav:"commentId,omitempty" json:"commentId,omitempty"`
	Timestamp   time.Time `dynamodbav:"timestamp" json:"timestamp"`
	TokenCount  int       `dynamodbav:"tokenCount" json:"tokenCount"`
	TTL         int64     `dynamodbav:"ttl" json:"ttl"`
}

func CreateConversationPK(tenantID, ticketKey string) string {
	return fmt.Sprintf("CONV#%s#%s", tenantID, ticketKey)
}

func CreateConversationSK(ts time.Time, seq int) string {
	return fmt.Sprintf("MSG#%s#%04d", ts.UTC().Format(time.RFC3339Nano), seq)
}

func DefaultTTL() int64 {
	return time.Now().Add(30 * 24 * time.Hour).Unix()
}
