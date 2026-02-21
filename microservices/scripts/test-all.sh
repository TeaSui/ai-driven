#!/bin/bash
set -e

echo "========================================"
echo "Running tests for all microservices"
echo "========================================"

SERVICES=("shared" "api-gateway" "auth-service" "workflow-service" "integration-service" "notification-service")
PASSED=()
FAILED=()

for service in "${SERVICES[@]}"; do
  echo ""
  echo "----------------------------------------"
  echo "Testing: $service"
  echo "----------------------------------------"

  if [ -d "$service" ]; then
    cd "$service"

    if [ ! -d "node_modules" ]; then
      echo "Installing dependencies for $service..."
      npm ci --silent
    fi

    if npm test -- --passWithNoTests 2>&1; then
      PASSED+=("$service")
      echo "✅ $service: PASSED"
    else
      FAILED+=("$service")
      echo "❌ $service: FAILED"
    fi

    cd ..
  else
    echo "⚠️  Directory not found: $service"
  fi
done

echo ""
echo "========================================"
echo "Test Summary"
echo "========================================"
echo "Passed: ${#PASSED[@]} / $((${#PASSED[@]} + ${#FAILED[@]}))"

for s in "${PASSED[@]}"; do
  echo "  ✅ $s"
done

for s in "${FAILED[@]}"; do
  echo "  ❌ $s"
done

if [ ${#FAILED[@]} -gt 0 ]; then
  echo ""
  echo "Some tests failed!"
  exit 1
fi

echo ""
echo "All tests passed! 🎉"
