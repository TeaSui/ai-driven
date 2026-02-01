#!/bin/bash

# Configuration
WEBHOOK_URL="https://ar93m3jwq5.execute-api.ap-southeast-1.amazonaws.com/prod/webhook"

# Mock Jira Payload
PAYLOAD='{
  "webhookEvent": "jira:issue_created",
  "issue": {
    "key": "AI-101",
    "fields": {
      "summary": "Create a new REST endpoint for user profiles",
      "description": "Please create a new GET /profiles/{id} endpoint in the backend service. It should return user details from the database.",
      "priority": { "name": "Medium" },
      "labels": ["backend", "api"],
      "status": { "name": "To Do" },
      "project": { "key": "AI" }
    }
  }
}'

echo "Sending mock Jira event to $WEBHOOK_URL..."
curl -X POST -H "Content-Type: application/json" -d "$PAYLOAD" "$WEBHOOK_URL"

echo -e "\n\nDone. Check the AWS Step Functions console for the execution: ai-driven-agent-workflow"
