package repository

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/google/uuid"
)

// AuditService records AI invocation artifacts to S3 for auditability.
type AuditService struct {
	s3Client   *s3.Client
	bucketName string
}

// NewAuditService creates a new S3-backed audit service.
func NewAuditService(s3Client *s3.Client, bucketName string) *AuditService {
	return &AuditService{
		s3Client:   s3Client,
		bucketName: bucketName,
	}
}

// RecordInvocation stores system prompt, user prompt, model response, and metadata
// as separate objects under an audit prefix in S3.
func (a *AuditService) RecordInvocation(
	ctx context.Context,
	ticketKey, systemPrompt, userPrompt, modelResponse string,
	metadata map[string]any,
) error {
	now := time.Now().UTC()
	prefix := fmt.Sprintf("audit/%d/%02d/%s/%d_%s",
		now.Year(), now.Month(), ticketKey, now.Unix(), uuid.New().String(),
	)

	files := map[string][]byte{
		"system_prompt.txt":  []byte(systemPrompt),
		"user_prompt.txt":    []byte(userPrompt),
		"model_response.txt": []byte(modelResponse),
	}

	metadataJSON, err := json.MarshalIndent(metadata, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal audit metadata: %w", err)
	}
	files["metadata.json"] = metadataJSON

	for name, content := range files {
		key := fmt.Sprintf("%s/%s", prefix, name)
		_, err := a.s3Client.PutObject(ctx, &s3.PutObjectInput{
			Bucket: aws.String(a.bucketName),
			Key:    aws.String(key),
			Body:   bytes.NewReader(content),
		})
		if err != nil {
			return fmt.Errorf("upload audit file %s: %w", name, err)
		}
	}

	return nil
}
