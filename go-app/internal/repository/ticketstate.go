package repository

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/expression"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/rs/zerolog/log"
)

// TicketStateRepository manages TicketState persistence in DynamoDB.
type TicketStateRepository struct {
	client    *dynamodb.Client
	tableName string
}

// NewTicketStateRepository creates a new repository for ticket state operations.
func NewTicketStateRepository(client *dynamodb.Client, tableName string) *TicketStateRepository {
	return &TicketStateRepository{
		client:    client,
		tableName: tableName,
	}
}

// Save persists the given TicketState, overwriting any existing item with the same key.
func (r *TicketStateRepository) Save(ctx context.Context, state *TicketState) error {
	item, err := marshalItem(*state)
	if err != nil {
		return fmt.Errorf("marshal ticket state: %w", err)
	}

	_, err = r.client.PutItem(ctx, &dynamodb.PutItemInput{
		TableName: aws.String(r.tableName),
		Item:      item,
	})
	if err != nil {
		return fmt.Errorf("put ticket state: %w", err)
	}
	return nil
}

// SaveIfNotExists stores the state only if no item with the same key exists.
// Returns true if the item was written, false if it already existed.
func (r *TicketStateRepository) SaveIfNotExists(ctx context.Context, state *TicketState) (bool, error) {
	item, err := marshalItem(*state)
	if err != nil {
		return false, fmt.Errorf("marshal ticket state: %w", err)
	}

	cond := expression.AttributeNotExists(expression.Name("PK"))
	expr, err := expression.NewBuilder().WithCondition(cond).Build()
	if err != nil {
		return false, fmt.Errorf("build condition expression: %w", err)
	}

	_, err = r.client.PutItem(ctx, &dynamodb.PutItemInput{
		TableName:                 aws.String(r.tableName),
		Item:                      item,
		ConditionExpression:       expr.Condition(),
		ExpressionAttributeNames:  expr.Names(),
		ExpressionAttributeValues: expr.Values(),
	})
	if err != nil {
		var ccf *types.ConditionalCheckFailedException
		if ok := isConditionCheckFailed(err, &ccf); ok {
			return false, nil
		}
		return false, fmt.Errorf("conditional put ticket state: %w", err)
	}
	return true, nil
}

// GetLatestState retrieves the current state for the given ticket.
func (r *TicketStateRepository) GetLatestState(ctx context.Context, tenantID, ticketID string) (*TicketState, error) {
	return r.Get(ctx, CreateTicketPK(tenantID, ticketID), CreateCurrentStateSK())
}

// UpdateStatus transitions the ticket to a new status, updating the current state
// and appending a historical state entry.
func (r *TicketStateRepository) UpdateStatus(ctx context.Context, tenantID, ticketID, ticketKey string, status ProcessingStatus) (*TicketState, error) {
	now := time.Now().UTC()
	pk := CreateTicketPK(tenantID, ticketID)

	// Update the current state item.
	update := expression.
		Set(expression.Name("status"), expression.Value(string(status))).
		Set(expression.Name("updatedAt"), expression.Value(now.Format(time.RFC3339))).
		Set(expression.Name("GSI1PK"), expression.Value(CreateStatusGSI1PK(status))).
		Set(expression.Name("GSI1SK"), expression.Value(pk)).
		Set(expression.Name("ticketId"), expression.Value(ticketID)).
		Set(expression.Name("ticketKey"), expression.Value(ticketKey))

	expr, err := expression.NewBuilder().WithUpdate(update).Build()
	if err != nil {
		return nil, fmt.Errorf("build update expression: %w", err)
	}

	result, err := r.client.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName:                 aws.String(r.tableName),
		Key:                       marshalKey(pk, CreateCurrentStateSK()),
		UpdateExpression:          expr.Update(),
		ExpressionAttributeNames:  expr.Names(),
		ExpressionAttributeValues: expr.Values(),
		ReturnValues:              types.ReturnValueAllNew,
	})
	if err != nil {
		return nil, fmt.Errorf("update ticket status: %w", err)
	}

	var updated TicketState
	if err := unmarshalItem(result.Attributes, &updated); err != nil {
		return nil, fmt.Errorf("unmarshal updated state: %w", err)
	}

	// Write historical state entry.
	historical := updated
	historical.SK = CreateStateSK(now)
	if saveErr := r.Save(ctx, &historical); saveErr != nil {
		log.Ctx(ctx).Warn().Err(saveErr).
			Str("ticketId", ticketID).
			Msg("Failed to write historical state entry")
	}

	return &updated, nil
}

// Get retrieves a single TicketState by primary key.
func (r *TicketStateRepository) Get(ctx context.Context, pk, sk string) (*TicketState, error) {
	result, err := r.client.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: aws.String(r.tableName),
		Key:       marshalKey(pk, sk),
	})
	if err != nil {
		return nil, fmt.Errorf("get ticket state: %w", err)
	}
	if result.Item == nil {
		return nil, nil
	}

	var state TicketState
	if err := unmarshalItem(result.Item, &state); err != nil {
		return nil, fmt.Errorf("unmarshal ticket state: %w", err)
	}
	return &state, nil
}

// GetTicketHistory retrieves all historical state entries for a ticket, ordered by time.
func (r *TicketStateRepository) GetTicketHistory(ctx context.Context, tenantID, ticketID string) ([]TicketState, error) {
	pk := CreateTicketPK(tenantID, ticketID)

	keyCond := expression.KeyAnd(
		expression.Key("PK").Equal(expression.Value(pk)),
		expression.Key("SK").BeginsWith("STATE#"),
	)
	expr, err := expression.NewBuilder().WithKeyCondition(keyCond).Build()
	if err != nil {
		return nil, fmt.Errorf("build key condition: %w", err)
	}

	result, err := r.client.Query(ctx, &dynamodb.QueryInput{
		TableName:                 aws.String(r.tableName),
		KeyConditionExpression:    expr.KeyCondition(),
		ExpressionAttributeNames:  expr.Names(),
		ExpressionAttributeValues: expr.Values(),
	})
	if err != nil {
		return nil, fmt.Errorf("query ticket history: %w", err)
	}

	states := make([]TicketState, 0, len(result.Items))
	for _, item := range result.Items {
		var s TicketState
		if err := unmarshalItem(item, &s); err != nil {
			return nil, fmt.Errorf("unmarshal history item: %w", err)
		}
		states = append(states, s)
	}
	return states, nil
}

// isConditionCheckFailed checks whether the error is a ConditionalCheckFailedException.
func isConditionCheckFailed(err error, target **types.ConditionalCheckFailedException) bool {
	var ccf *types.ConditionalCheckFailedException
	if errors.As(err, &ccf) {
		*target = ccf
		return true
	}
	return false
}
