#!/bin/bash
# Run tests for all microservices
set -e

echo "=== Running Tests for CRM Microservices ==="

# Test common library
echo "\n[1/5] Testing common-lib..."
cd common-lib && mvn test && cd ..

# Test Auth Service
echo "\n[2/5] Testing auth-service..."
cd auth-service && mvn test && cd ..

# Test Workflow Service
echo "\n[3/5] Testing workflow-service..."
cd workflow-service && mvn test && cd ..

# Test Integration Service
echo "\n[4/5] Testing integration-service..."
cd integration-service && mvn test && cd ..

# Test Notification Service
echo "\n[5/5] Testing notification-service..."
cd notification-service && mvn test && cd ..

echo "\n=== All tests passed! ==="
