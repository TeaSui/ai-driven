package repository

import (
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// marshalKey creates a DynamoDB key map from partition key and sort key strings.
func marshalKey(pk, sk string) map[string]types.AttributeValue {
	return map[string]types.AttributeValue{
		"PK": &types.AttributeValueMemberS{Value: pk},
		"SK": &types.AttributeValueMemberS{Value: sk},
	}
}

// marshalItem marshals a Go struct into a DynamoDB attribute value map.
func marshalItem(item any) (map[string]types.AttributeValue, error) {
	return attributevalue.MarshalMap(item)
}

// unmarshalItem unmarshals a DynamoDB attribute value map into a Go struct.
func unmarshalItem(item map[string]types.AttributeValue, out any) error {
	return attributevalue.UnmarshalMap(item, out)
}
