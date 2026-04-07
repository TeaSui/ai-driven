package guardrail

import (
	"context"
	"fmt"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/expression"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/rs/zerolog/log"
)

// ApprovalTTLSeconds is the time-to-live for pending approvals (24 hours).
const ApprovalTTLSeconds = 86400

// PendingApproval represents a pending approval record stored in DynamoDB.
type PendingApproval struct {
	PK             string `dynamodbav:"PK"`
	SK             string `dynamodbav:"SK"`
	ToolCallID     string `dynamodbav:"toolCallId"`
	ToolName       string `dynamodbav:"toolName"`
	ToolInputJSON  string `dynamodbav:"toolInputJson"`
	RiskLevel      string `dynamodbav:"riskLevel"`
	ApprovalPrompt string `dynamodbav:"approvalPrompt"`
	RequestedBy    string `dynamodbav:"requestedBy"`
	RequestedAt    string `dynamodbav:"requestedAt"`
	Status         string `dynamodbav:"status"`
	TTL            int64  `dynamodbav:"ttl"`
}

// ApprovalStore persists and retrieves pending approvals in DynamoDB.
type ApprovalStore struct {
	client    *dynamodb.Client
	tableName string
}

// NewApprovalStore creates a new DynamoDB-backed approval store.
func NewApprovalStore(client *dynamodb.Client, tableName string) *ApprovalStore {
	return &ApprovalStore{
		client:    client,
		tableName: tableName,
	}
}

// StorePending saves a new pending approval record.
// PK: "AGENT#{ticketKey}", SK: "APPROVAL#{timestamp}", status: "PENDING", TTL: 24h.
func (s *ApprovalStore) StorePending(
	ctx context.Context,
	ticketKey, toolCallID, toolName, toolInputJSON string,
	riskLevel RiskLevel,
	prompt, requestedBy string,
) error {
	now := time.Now().UTC()
	approval := PendingApproval{
		PK:             fmt.Sprintf("AGENT#%s", ticketKey),
		SK:             fmt.Sprintf("APPROVAL#%s", now.Format(time.RFC3339Nano)),
		ToolCallID:     toolCallID,
		ToolName:       toolName,
		ToolInputJSON:  toolInputJSON,
		RiskLevel:      riskLevel.String(),
		ApprovalPrompt: prompt,
		RequestedBy:    requestedBy,
		RequestedAt:    now.Format(time.RFC3339),
		Status:         "PENDING",
		TTL:            now.Unix() + ApprovalTTLSeconds,
	}

	item, err := attributevalue.MarshalMap(approval)
	if err != nil {
		return fmt.Errorf("marshal pending approval: %w", err)
	}

	if s.client == nil {
		return fmt.Errorf("put pending approval: DynamoDB client is nil")
	}

	_, err = s.client.PutItem(ctx, &dynamodb.PutItemInput{
		TableName: aws.String(s.tableName),
		Item:      item,
	})
	if err != nil {
		return fmt.Errorf("put pending approval: %w", err)
	}

	log.Ctx(ctx).Info().
		Str("ticketKey", ticketKey).
		Str("toolName", toolName).
		Str("riskLevel", riskLevel.String()).
		Msg("Stored pending approval")

	return nil
}

// GetLatestPending retrieves the most recent pending approval for a ticket.
// Queries PK, SK begins_with "APPROVAL#", filters status=PENDING, newest first, limit 1.
func (s *ApprovalStore) GetLatestPending(ctx context.Context, ticketKey string) (*PendingApproval, error) {
	if s.client == nil {
		return nil, fmt.Errorf("query pending approval: DynamoDB client is nil")
	}

	pk := fmt.Sprintf("AGENT#%s", ticketKey)

	keyCond := expression.KeyAnd(
		expression.Key("PK").Equal(expression.Value(pk)),
		expression.Key("SK").BeginsWith("APPROVAL#"),
	)
	filter := expression.Name("status").Equal(expression.Value("PENDING"))

	expr, err := expression.NewBuilder().
		WithKeyCondition(keyCond).
		WithFilter(filter).
		Build()
	if err != nil {
		return nil, fmt.Errorf("build expression: %w", err)
	}

	limit := int32(1)
	result, err := s.client.Query(ctx, &dynamodb.QueryInput{
		TableName:                 aws.String(s.tableName),
		KeyConditionExpression:    expr.KeyCondition(),
		FilterExpression:          expr.Filter(),
		ExpressionAttributeNames:  expr.Names(),
		ExpressionAttributeValues: expr.Values(),
		ScanIndexForward:          aws.Bool(false),
		Limit:                     &limit,
	})
	if err != nil {
		return nil, fmt.Errorf("query pending approval: %w", err)
	}

	if len(result.Items) == 0 {
		return nil, nil
	}

	var approval PendingApproval
	if err := attributevalue.UnmarshalMap(result.Items[0], &approval); err != nil {
		return nil, fmt.Errorf("unmarshal pending approval: %w", err)
	}

	return &approval, nil
}

// ConsumeApproval transitions a pending approval to APPROVED status and records the approval time.
func (s *ApprovalStore) ConsumeApproval(ctx context.Context, ticketKey, sk string) error {
	if s.client == nil {
		return fmt.Errorf("update approval status: DynamoDB client is nil")
	}

	pk := fmt.Sprintf("AGENT#%s", ticketKey)
	now := time.Now().UTC().Format(time.RFC3339)

	update := expression.Set(
		expression.Name("status"), expression.Value("APPROVED"),
	).Set(
		expression.Name("approvedAt"), expression.Value(now),
	)

	expr, err := expression.NewBuilder().WithUpdate(update).Build()
	if err != nil {
		return fmt.Errorf("build update expression: %w", err)
	}

	_, err = s.client.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName: aws.String(s.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: pk},
			"SK": &types.AttributeValueMemberS{Value: sk},
		},
		UpdateExpression:          expr.Update(),
		ExpressionAttributeNames:  expr.Names(),
		ExpressionAttributeValues: expr.Values(),
	})
	if err != nil {
		return fmt.Errorf("update approval status: %w", err)
	}

	log.Ctx(ctx).Info().
		Str("ticketKey", ticketKey).
		Str("sk", sk).
		Msg("Consumed approval")

	return nil
}
