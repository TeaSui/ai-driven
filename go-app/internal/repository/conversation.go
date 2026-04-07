package repository

import (
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/expression"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

const conversationQueryLimit = 100

// ConversationRepository defines operations for persisting and retrieving conversation messages.
type ConversationRepository interface {
	Save(ctx context.Context, message *model.ConversationMessage) error
	GetConversation(ctx context.Context, tenantID, ticketKey string) ([]model.ConversationMessage, error)
	GetTotalTokens(ctx context.Context, tenantID, ticketKey string) (int, error)
	DeleteConversation(ctx context.Context, tenantID, ticketKey string) error
}

// DynamoConversationRepository implements ConversationRepository using DynamoDB.
type DynamoConversationRepository struct {
	client    *dynamodb.Client
	tableName string
}

// NewDynamoConversationRepository creates a new DynamoDB-backed conversation repository.
func NewDynamoConversationRepository(client *dynamodb.Client, tableName string) *DynamoConversationRepository {
	return &DynamoConversationRepository{
		client:    client,
		tableName: tableName,
	}
}

// Save persists a single conversation message.
func (r *DynamoConversationRepository) Save(ctx context.Context, message *model.ConversationMessage) error {
	item, err := marshalItem(*message)
	if err != nil {
		return fmt.Errorf("marshal conversation message: %w", err)
	}

	_, err = r.client.PutItem(ctx, &dynamodb.PutItemInput{
		TableName: aws.String(r.tableName),
		Item:      item,
	})
	if err != nil {
		return fmt.Errorf("put conversation message: %w", err)
	}
	return nil
}

// GetConversation retrieves up to 100 messages for a conversation, in chronological order.
func (r *DynamoConversationRepository) GetConversation(ctx context.Context, tenantID, ticketKey string) ([]model.ConversationMessage, error) {
	pk := model.CreateConversationPK(tenantID, ticketKey)

	keyCond := expression.KeyAnd(
		expression.Key("PK").Equal(expression.Value(pk)),
		expression.Key("SK").BeginsWith("MSG#"),
	)
	expr, err := expression.NewBuilder().WithKeyCondition(keyCond).Build()
	if err != nil {
		return nil, fmt.Errorf("build key condition: %w", err)
	}

	// Query in reverse (newest first) to get the latest messages, then reverse for chronological.
	limit := int32(conversationQueryLimit)
	result, err := r.client.Query(ctx, &dynamodb.QueryInput{
		TableName:                 aws.String(r.tableName),
		KeyConditionExpression:    expr.KeyCondition(),
		ExpressionAttributeNames:  expr.Names(),
		ExpressionAttributeValues: expr.Values(),
		ScanIndexForward:          aws.Bool(false),
		Limit:                     &limit,
	})
	if err != nil {
		return nil, fmt.Errorf("query conversation: %w", err)
	}

	messages := make([]model.ConversationMessage, 0, len(result.Items))
	for _, item := range result.Items {
		var msg model.ConversationMessage
		if err := unmarshalItem(item, &msg); err != nil {
			return nil, fmt.Errorf("unmarshal conversation message: %w", err)
		}
		messages = append(messages, msg)
	}

	// Reverse to chronological order.
	for i, j := 0, len(messages)-1; i < j; i, j = i+1, j-1 {
		messages[i], messages[j] = messages[j], messages[i]
	}

	return messages, nil
}

// GetTotalTokens sums token counts across all messages in the conversation.
func (r *DynamoConversationRepository) GetTotalTokens(ctx context.Context, tenantID, ticketKey string) (int, error) {
	messages, err := r.GetConversation(ctx, tenantID, ticketKey)
	if err != nil {
		return 0, err
	}

	total := 0
	for i := range messages {
		total += messages[i].TokenCount
	}
	return total, nil
}

// DeleteConversation removes all messages for a conversation using batch deletes.
func (r *DynamoConversationRepository) DeleteConversation(ctx context.Context, tenantID, ticketKey string) error {
	pk := model.CreateConversationPK(tenantID, ticketKey)

	keyCond := expression.KeyAnd(
		expression.Key("PK").Equal(expression.Value(pk)),
		expression.Key("SK").BeginsWith("MSG#"),
	)
	expr, err := expression.NewBuilder().WithKeyCondition(keyCond).Build()
	if err != nil {
		return fmt.Errorf("build key condition: %w", err)
	}

	// Project only keys to minimize read cost.
	projExpr := "PK, SK"
	result, err := r.client.Query(ctx, &dynamodb.QueryInput{
		TableName:                 aws.String(r.tableName),
		KeyConditionExpression:    expr.KeyCondition(),
		ExpressionAttributeNames:  expr.Names(),
		ExpressionAttributeValues: expr.Values(),
		ProjectionExpression:      &projExpr,
	})
	if err != nil {
		return fmt.Errorf("query conversation for delete: %w", err)
	}

	if len(result.Items) == 0 {
		return nil
	}

	// BatchWriteItem supports up to 25 items per call.
	const batchSize = 25
	for i := 0; i < len(result.Items); i += batchSize {
		end := i + batchSize
		if end > len(result.Items) {
			end = len(result.Items)
		}

		requests := make([]types.WriteRequest, 0, end-i)
		for _, item := range result.Items[i:end] {
			requests = append(requests, types.WriteRequest{
				DeleteRequest: &types.DeleteRequest{
					Key: map[string]types.AttributeValue{
						"PK": item["PK"],
						"SK": item["SK"],
					},
				},
			})
		}

		_, err := r.client.BatchWriteItem(ctx, &dynamodb.BatchWriteItemInput{
			RequestItems: map[string][]types.WriteRequest{
				r.tableName: requests,
			},
		})
		if err != nil {
			log.Ctx(ctx).Warn().Err(err).
				Str("tenantID", tenantID).
				Str("ticketKey", ticketKey).
				Int("batch", i/batchSize).
				Msg("Failed to batch delete conversation messages")
			return fmt.Errorf("batch delete conversation messages: %w", err)
		}
	}

	return nil
}
