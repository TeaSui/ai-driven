#!/bin/bash

echo "Starting development environment..."

# Start infrastructure only
docker-compose up -d redis rabbitmq postgres-auth postgres-workflow postgres-integration postgres-notification

echo "Waiting for services to be healthy..."
sleep 10

echo "Infrastructure ready!"
echo ""
echo "Start individual services with:"
echo "  cd auth-service && npm run dev"
echo "  cd workflow-service && npm run dev"
echo "  cd integration-service && npm run dev"
echo "  cd notification-service && npm run dev"
echo "  cd api-gateway && npm run dev"
