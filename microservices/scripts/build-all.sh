#!/bin/bash
set -e

echo "Building all microservices..."

SERVICES=("shared" "api-gateway" "auth-service" "workflow-service" "integration-service" "notification-service")

for service in "${SERVICES[@]}"; do
  echo "Building $service..."
  cd "$service"
  npm ci --silent
  npm run build
  cd ..
  echo "✅ $service built"
done

echo "All services built successfully!"
