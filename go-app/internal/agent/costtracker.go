package agent

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/rs/zerolog/log"
)

const (
	skCostSummary  = "COST_SUMMARY"
	costTTLSeconds = 30 * 24 * 3600 // 30 days
)

// DynamoDBClient defines the subset of dynamodb.Client used by DynamoCostTracker.
// This enables unit testing with a mock implementation.
type DynamoDBClient interface {
	GetItem(ctx context.Context, params *dynamodb.GetItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error)
	UpdateItem(ctx context.Context, params *dynamodb.UpdateItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.UpdateItemOutput, error)
}

// DynamoCostTracker tracks cumulative token cost per ticket using DynamoDB.
// It enforces a configurable budget to prevent cost runaway.
//
// Key schema (single-table design):
//   - PK: AGENT#{ticketKey}
//   - SK: COST_SUMMARY
type DynamoCostTracker struct {
	client             DynamoDBClient
	tableName          string
	maxTokensPerTicket int
	nowFunc            func() time.Time // for testing; defaults to time.Now
}

// NewDynamoCostTracker creates a new DynamoDB-backed cost tracker.
func NewDynamoCostTracker(client DynamoDBClient, tableName string, maxTokensPerTicket int) *DynamoCostTracker {
	return &DynamoCostTracker{
		client:             client,
		tableName:          tableName,
		maxTokensPerTicket: maxTokensPerTicket,
		nowFunc:            time.Now,
	}
}

// createCostPK builds the partition key for a cost tracking entry.
func createCostPK(ticketKey string) string {
	return fmt.Sprintf("AGENT#%s", ticketKey)
}

// HasRemainingBudget queries DynamoDB for the current token count and returns
// true if the ticket is still within its budget. DynamoDB errors are logged
// and treated as "has budget" (fail open) to avoid blocking processing.
func (t *DynamoCostTracker) HasRemainingBudget(ticketKey string) bool {
	used := t.getTotalTokensUsed(ticketKey)
	withinBudget := used < t.maxTokensPerTicket
	if !withinBudget {
		log.Warn().
			Str("ticketKey", ticketKey).
			Int("used", used).
			Int("budget", t.maxTokensPerTicket).
			Msg("Ticket exceeded token budget")
	}
	return withinBudget
}

// AddTokens atomically increments the token counter for a ticket using
// DynamoDB's ADD expression. Non-positive token values are ignored.
// DynamoDB errors are logged but do not propagate (non-fatal).
func (t *DynamoCostTracker) AddTokens(ticketKey string, tokens int) {
	if tokens <= 0 {
		return
	}

	pk := createCostPK(ticketKey)
	now := t.nowFunc()
	ttl := now.Add(costTTLSeconds * time.Second).Unix()

	_, err := t.client.UpdateItem(context.Background(), &dynamodb.UpdateItemInput{
		TableName: aws.String(t.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: pk},
			"SK": &types.AttributeValueMemberS{Value: skCostSummary},
		},
		UpdateExpression: aws.String("ADD totalTokens :t SET updatedAt = :now, #ttl = :ttl"),
		ExpressionAttributeNames: map[string]string{
			"#ttl": "ttl",
		},
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":t":   &types.AttributeValueMemberN{Value: strconv.Itoa(tokens)},
			":now": &types.AttributeValueMemberS{Value: now.UTC().Format(time.RFC3339)},
			":ttl": &types.AttributeValueMemberN{Value: strconv.FormatInt(ttl, 10)},
		},
	})
	if err != nil {
		log.Error().Err(err).
			Str("ticketKey", ticketKey).
			Int("tokens", tokens).
			Msg("Failed to update cost tracker")
		return
	}

	log.Debug().
		Str("ticketKey", ticketKey).
		Int("tokens", tokens).
		Msg("Added tokens to cost tracker")
}

// getTotalTokensUsed reads the current token count from DynamoDB.
// Returns 0 if the item does not exist or on error (fail open).
func (t *DynamoCostTracker) getTotalTokensUsed(ticketKey string) int {
	pk := createCostPK(ticketKey)

	output, err := t.client.GetItem(context.Background(), &dynamodb.GetItemInput{
		TableName: aws.String(t.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: pk},
			"SK": &types.AttributeValueMemberS{Value: skCostSummary},
		},
	})
	if err != nil {
		log.Warn().Err(err).
			Str("ticketKey", ticketKey).
			Msg("Failed to read cost summary, failing open")
		return 0
	}

	if output.Item == nil {
		return 0
	}

	totalAttr, ok := output.Item["totalTokens"]
	if !ok {
		return 0
	}

	nAttr, ok := totalAttr.(*types.AttributeValueMemberN)
	if !ok {
		return 0
	}

	val, err := strconv.Atoi(nAttr.Value)
	if err != nil {
		log.Warn().Err(err).
			Str("ticketKey", ticketKey).
			Str("rawValue", nAttr.Value).
			Msg("Failed to parse totalTokens")
		return 0
	}

	return val
}
