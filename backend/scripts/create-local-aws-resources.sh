#!/bin/bash
# Create DynamoDB tables + SQS queues in localstack for local dev.
# Run once after `docker compose up -d` (or whenever localstack is empty).
set -euo pipefail

ENDPOINT="${AI_DRIVEN_DYNAMODB_ENDPOINT_URL:-http://localhost:4566}"
REGION="${AI_DRIVEN_AWS_REGION:-eu-central-1}"
PREFIX="${AI_DRIVEN_DYNAMODB_TABLE_PREFIX:-ai-driven}"

aws_ddb() { AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws dynamodb --endpoint-url="$ENDPOINT" --region="$REGION" "$@"; }
aws_sqs() { AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws sqs       --endpoint-url="$ENDPOINT" --region="$REGION" "$@"; }

echo "▶ Creating DynamoDB tables on $ENDPOINT …"
aws_ddb describe-table --table-name "$PREFIX-webhook-events" >/dev/null 2>&1 || aws_ddb create-table \
  --table-name "$PREFIX-webhook-events" \
  --attribute-definitions AttributeName=event_id,AttributeType=S AttributeName=source_timestamp,AttributeType=S \
  --key-schema AttributeName=event_id,KeyType=HASH AttributeName=source_timestamp,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST >/dev/null && echo "  ✓ $PREFIX-webhook-events"

aws_ddb describe-table --table-name "$PREFIX-agent-sessions" >/dev/null 2>&1 || aws_ddb create-table \
  --table-name "$PREFIX-agent-sessions" \
  --attribute-definitions AttributeName=session_id,AttributeType=S AttributeName=timestamp,AttributeType=S \
  --key-schema AttributeName=session_id,KeyType=HASH AttributeName=timestamp,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST >/dev/null && echo "  ✓ $PREFIX-agent-sessions"

aws_ddb describe-table --table-name "$PREFIX-task-state-machine" >/dev/null 2>&1 || aws_ddb create-table \
  --table-name "$PREFIX-task-state-machine" \
  --attribute-definitions AttributeName=task_id,AttributeType=S AttributeName=state_timestamp,AttributeType=S \
  --key-schema AttributeName=task_id,KeyType=HASH AttributeName=state_timestamp,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST >/dev/null && echo "  ✓ $PREFIX-task-state-machine"

echo "▶ Creating SQS queues …"
for name in task-queue review-queue devops-queue; do
  aws_sqs get-queue-url --queue-name "$PREFIX-$name.fifo" >/dev/null 2>&1 || aws_sqs create-queue \
    --queue-name "$PREFIX-$name.fifo" \
    --attributes '{"FifoQueue":"true","ContentBasedDeduplication":"true"}' >/dev/null && echo "  ✓ $PREFIX-$name.fifo"
done

echo "done."
