package repository

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/rs/zerolog/log"
)

// ErrRateLimitExceeded is returned when the rate limit has been reached.
var ErrRateLimitExceeded = fmt.Errorf("rate limit exceeded")

// DynamoRateLimiter implements a fixed-window rate limiter backed by DynamoDB.
type DynamoRateLimiter struct {
	client    *dynamodb.Client
	tableName string
}

// NewDynamoRateLimiter creates a new DynamoDB-backed rate limiter.
func NewDynamoRateLimiter(client *dynamodb.Client, tableName string) *DynamoRateLimiter {
	return &DynamoRateLimiter{
		client:    client,
		tableName: tableName,
	}
}

// CreateRateLimitPK builds the partition key for a rate limit entry.
func CreateRateLimitPK(key string, windowStart time.Time) string {
	hourISO := windowStart.UTC().Truncate(time.Hour).Format("2006-01-02T15")
	return fmt.Sprintf("RATELIMIT#%s#%s", key, hourISO)
}

// ConsumeOrThrow atomically increments the request counter. If the limit is
// reached, it returns ErrRateLimitExceeded. DynamoDB errors are logged and
// swallowed (fail open).
func (r *DynamoRateLimiter) ConsumeOrThrow(ctx context.Context, key string, maxPerHour int) error {
	now := time.Now().UTC()
	windowStart := now.Truncate(time.Hour)
	pk := CreateRateLimitPK(key, windowStart)
	sk := "META"
	ttl := windowStart.Add(2 * time.Hour).Unix()

	_, err := r.client.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName: aws.String(r.tableName),
		Key:       marshalKey(pk, sk),
		UpdateExpression: aws.String(
			"SET requestCount = if_not_exists(requestCount, :zero) + :one, #ttl = :ttl",
		),
		ConditionExpression: aws.String(
			"attribute_not_exists(requestCount) OR requestCount < :max",
		),
		ExpressionAttributeNames: map[string]string{
			"#ttl": "ttl",
		},
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":zero": &types.AttributeValueMemberN{Value: "0"},
			":one":  &types.AttributeValueMemberN{Value: "1"},
			":max":  &types.AttributeValueMemberN{Value: fmt.Sprintf("%d", maxPerHour)},
			":ttl":  &types.AttributeValueMemberN{Value: fmt.Sprintf("%d", ttl)},
		},
	})
	if err != nil {
		// Check for condition failure (rate limit exceeded).
		var ccErr *types.ConditionalCheckFailedException
		if errors.As(err, &ccErr) {
			return fmt.Errorf("%w: key=%s maxPerHour=%d", ErrRateLimitExceeded, key, maxPerHour)
		}
		// Fail open on other DynamoDB errors.
		log.Ctx(ctx).Warn().Err(err).
			Str("key", key).
			Int("maxPerHour", maxPerHour).
			Msg("Rate limiter DynamoDB error, failing open")
		return nil
	}

	return nil
}
