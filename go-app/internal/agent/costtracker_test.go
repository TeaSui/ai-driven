package agent

import (
	"context"
	"fmt"
	"strconv"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// --- Mock DynamoDB Client ---

type mockDynamoDBClient struct {
	getItemFunc    func(ctx context.Context, params *dynamodb.GetItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error)
	updateItemFunc func(ctx context.Context, params *dynamodb.UpdateItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.UpdateItemOutput, error)

	// Capture calls for assertions
	getItemCalls    []*dynamodb.GetItemInput
	updateItemCalls []*dynamodb.UpdateItemInput
}

func (m *mockDynamoDBClient) GetItem(ctx context.Context, params *dynamodb.GetItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
	m.getItemCalls = append(m.getItemCalls, params)
	if m.getItemFunc != nil {
		return m.getItemFunc(ctx, params, optFns...)
	}
	return &dynamodb.GetItemOutput{}, nil
}

func (m *mockDynamoDBClient) UpdateItem(ctx context.Context, params *dynamodb.UpdateItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.UpdateItemOutput, error) {
	m.updateItemCalls = append(m.updateItemCalls, params)
	if m.updateItemFunc != nil {
		return m.updateItemFunc(ctx, params, optFns...)
	}
	return &dynamodb.UpdateItemOutput{}, nil
}

// --- Tests ---

func TestCreateCostPK(t *testing.T) {
	assert.Equal(t, "AGENT#PROJ-123", createCostPK("PROJ-123"))
	assert.Equal(t, "AGENT#ABC-1", createCostPK("ABC-1"))
}

func TestNewDynamoCostTracker(t *testing.T) {
	mock := &mockDynamoDBClient{}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	assert.NotNil(t, tracker)
	assert.Equal(t, "test-table", tracker.tableName)
	assert.Equal(t, 100000, tracker.maxTokensPerTicket)
}

func TestHasRemainingBudget_NoPriorUsage(t *testing.T) {
	mock := &mockDynamoDBClient{
		getItemFunc: func(_ context.Context, _ *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
			return &dynamodb.GetItemOutput{Item: nil}, nil
		},
	}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	result := tracker.HasRemainingBudget("PROJ-123")

	assert.True(t, result)
	require.Len(t, mock.getItemCalls, 1)
	assert.Equal(t, "AGENT#PROJ-123", mock.getItemCalls[0].Key["PK"].(*types.AttributeValueMemberS).Value)
	assert.Equal(t, "COST_SUMMARY", mock.getItemCalls[0].Key["SK"].(*types.AttributeValueMemberS).Value)
}

func TestHasRemainingBudget_UnderBudget(t *testing.T) {
	mock := &mockDynamoDBClient{
		getItemFunc: func(_ context.Context, _ *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
			return &dynamodb.GetItemOutput{
				Item: map[string]types.AttributeValue{
					"totalTokens": &types.AttributeValueMemberN{Value: "50000"},
				},
			}, nil
		},
	}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	assert.True(t, tracker.HasRemainingBudget("PROJ-123"))
}

func TestHasRemainingBudget_ExactlyAtBudget(t *testing.T) {
	mock := &mockDynamoDBClient{
		getItemFunc: func(_ context.Context, _ *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
			return &dynamodb.GetItemOutput{
				Item: map[string]types.AttributeValue{
					"totalTokens": &types.AttributeValueMemberN{Value: "100000"},
				},
			}, nil
		},
	}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	assert.False(t, tracker.HasRemainingBudget("PROJ-123"))
}

func TestHasRemainingBudget_OverBudget(t *testing.T) {
	mock := &mockDynamoDBClient{
		getItemFunc: func(_ context.Context, _ *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
			return &dynamodb.GetItemOutput{
				Item: map[string]types.AttributeValue{
					"totalTokens": &types.AttributeValueMemberN{Value: "150000"},
				},
			}, nil
		},
	}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	assert.False(t, tracker.HasRemainingBudget("PROJ-123"))
}

func TestHasRemainingBudget_DynamoError_FailsOpen(t *testing.T) {
	mock := &mockDynamoDBClient{
		getItemFunc: func(_ context.Context, _ *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
			return nil, fmt.Errorf("connection timeout")
		},
	}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	// Should return true (fail open) when DynamoDB is unavailable
	assert.True(t, tracker.HasRemainingBudget("PROJ-123"))
}

func TestHasRemainingBudget_MissingTotalTokensAttribute(t *testing.T) {
	mock := &mockDynamoDBClient{
		getItemFunc: func(_ context.Context, _ *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
			return &dynamodb.GetItemOutput{
				Item: map[string]types.AttributeValue{
					"updatedAt": &types.AttributeValueMemberS{Value: "2026-01-01T00:00:00Z"},
				},
			}, nil
		},
	}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	assert.True(t, tracker.HasRemainingBudget("PROJ-123"))
}

func TestHasRemainingBudget_InvalidNumberAttribute(t *testing.T) {
	mock := &mockDynamoDBClient{
		getItemFunc: func(_ context.Context, _ *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
			return &dynamodb.GetItemOutput{
				Item: map[string]types.AttributeValue{
					"totalTokens": &types.AttributeValueMemberN{Value: "not-a-number"},
				},
			}, nil
		},
	}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	// Should fail open when parsing fails
	assert.True(t, tracker.HasRemainingBudget("PROJ-123"))
}

func TestAddTokens_Success(t *testing.T) {
	mock := &mockDynamoDBClient{}
	fixedTime := time.Date(2026, 4, 7, 12, 0, 0, 0, time.UTC)
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)
	tracker.nowFunc = func() time.Time { return fixedTime }

	tracker.AddTokens("PROJ-123", 500)

	require.Len(t, mock.updateItemCalls, 1)
	call := mock.updateItemCalls[0]

	// Verify table name
	assert.Equal(t, "test-table", *call.TableName)

	// Verify key
	assert.Equal(t, "AGENT#PROJ-123", call.Key["PK"].(*types.AttributeValueMemberS).Value)
	assert.Equal(t, "COST_SUMMARY", call.Key["SK"].(*types.AttributeValueMemberS).Value)

	// Verify update expression uses ADD
	assert.Equal(t, "ADD totalTokens :t SET updatedAt = :now, #ttl = :ttl", *call.UpdateExpression)

	// Verify expression attribute names
	assert.Equal(t, "ttl", call.ExpressionAttributeNames["#ttl"])

	// Verify expression attribute values
	assert.Equal(t, "500", call.ExpressionAttributeValues[":t"].(*types.AttributeValueMemberN).Value)
	assert.Equal(t, "2026-04-07T12:00:00Z", call.ExpressionAttributeValues[":now"].(*types.AttributeValueMemberS).Value)

	// Verify TTL is 30 days from now
	expectedTTL := fixedTime.Add(costTTLSeconds * time.Second).Unix()
	actualTTL := call.ExpressionAttributeValues[":ttl"].(*types.AttributeValueMemberN).Value
	assert.Equal(t, strconv.FormatInt(expectedTTL, 10), actualTTL)
}

func TestAddTokens_ZeroTokens_NoOp(t *testing.T) {
	mock := &mockDynamoDBClient{}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	tracker.AddTokens("PROJ-123", 0)

	assert.Empty(t, mock.updateItemCalls)
}

func TestAddTokens_NegativeTokens_NoOp(t *testing.T) {
	mock := &mockDynamoDBClient{}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	tracker.AddTokens("PROJ-123", -10)

	assert.Empty(t, mock.updateItemCalls)
}

func TestAddTokens_DynamoError_NonFatal(t *testing.T) {
	mock := &mockDynamoDBClient{
		updateItemFunc: func(_ context.Context, _ *dynamodb.UpdateItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.UpdateItemOutput, error) {
			return nil, fmt.Errorf("throttling exception")
		},
	}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	// Should not panic; error is logged but swallowed
	tracker.AddTokens("PROJ-123", 500)

	assert.Len(t, mock.updateItemCalls, 1)
}

func TestDynamoCostTracker_ImplementsCostTracker(t *testing.T) {
	mock := &mockDynamoDBClient{}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	// Compile-time check that DynamoCostTracker implements CostTracker
	var _ CostTracker = tracker
}

func TestAddTokens_CorrectTableName(t *testing.T) {
	mock := &mockDynamoDBClient{}
	tracker := NewDynamoCostTracker(mock, "my-custom-table", 100000)

	tracker.AddTokens("PROJ-1", 100)

	require.Len(t, mock.updateItemCalls, 1)
	assert.Equal(t, "my-custom-table", *mock.updateItemCalls[0].TableName)
}

func TestHasRemainingBudget_CorrectTableName(t *testing.T) {
	mock := &mockDynamoDBClient{
		getItemFunc: func(_ context.Context, params *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
			assert.Equal(t, "my-custom-table", *params.TableName)
			return &dynamodb.GetItemOutput{Item: nil}, nil
		},
	}
	tracker := NewDynamoCostTracker(mock, "my-custom-table", 100000)

	tracker.HasRemainingBudget("PROJ-1")
}

func TestHasRemainingBudget_BudgetBoundary_OneUnder(t *testing.T) {
	mock := &mockDynamoDBClient{
		getItemFunc: func(_ context.Context, _ *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
			return &dynamodb.GetItemOutput{
				Item: map[string]types.AttributeValue{
					"totalTokens": &types.AttributeValueMemberN{Value: "99999"},
				},
			}, nil
		},
	}
	tracker := NewDynamoCostTracker(mock, "test-table", 100000)

	assert.True(t, tracker.HasRemainingBudget("PROJ-123"))
}
