package repository

import (
	"context"
	"fmt"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

const shadowTTLDays = 14

// ShadowRecordWriter writes agent responses to DynamoDB for shadow mode comparison.
type ShadowRecordWriter struct {
	client    *dynamodb.Client
	tableName string
	prefix    string // "GO#" or "JAVA#"
}

// NewShadowRecordWriter creates a writer with the given prefix (e.g., "GO#").
func NewShadowRecordWriter(client *dynamodb.Client, tableName, prefix string) *ShadowRecordWriter {
	return &ShadowRecordWriter{
		client:    client,
		tableName: tableName,
		prefix:    prefix,
	}
}

type shadowRecord struct {
	PK         string   `dynamodbav:"PK"`
	SK         string   `dynamodbav:"SK"`
	GSI1PK     string   `dynamodbav:"GSI1PK"`
	GSI1SK     string   `dynamodbav:"GSI1SK"`
	TicketKey  string   `dynamodbav:"ticketKey"`
	Text       string   `dynamodbav:"text"`
	ToolsUsed  []string `dynamodbav:"toolsUsed"`
	TokenCount int      `dynamodbav:"tokenCount"`
	TurnCount  int      `dynamodbav:"turnCount"`
	Timestamp  string   `dynamodbav:"timestamp"`
	TTL        int64    `dynamodbav:"ttl"`
}

// Write records an agent response for shadow mode comparison.
func (w *ShadowRecordWriter) Write(ctx context.Context, ticketKey string, response *model.AgentResponse) {
	now := time.Now()
	record := shadowRecord{
		PK:         fmt.Sprintf("SHADOW#%s%s", w.prefix, ticketKey),
		SK:         now.Format(time.RFC3339Nano),
		GSI1PK:     w.prefix + "SHADOW_RESPONSE",
		GSI1SK:     now.Format(time.RFC3339),
		TicketKey:  ticketKey,
		Text:       response.Text,
		ToolsUsed:  response.ToolsUsed,
		TokenCount: response.TokenCount,
		TurnCount:  response.TurnCount,
		Timestamp:  now.Format(time.RFC3339),
		TTL:        now.Add(shadowTTLDays * 24 * time.Hour).Unix(),
	}

	item, err := attributevalue.MarshalMap(record)
	if err != nil {
		log.Ctx(ctx).Error().Err(err).Str("ticketKey", ticketKey).Msg("shadow: failed to marshal record")
		return
	}

	_, err = w.client.PutItem(ctx, &dynamodb.PutItemInput{
		TableName: aws.String(w.tableName),
		Item:      item,
	})
	if err != nil {
		log.Ctx(ctx).Error().Err(err).Str("ticketKey", ticketKey).Msg("shadow: failed to write record")
		return
	}

	log.Ctx(ctx).Debug().Str("ticketKey", ticketKey).Msg("shadow: recorded response")
}
