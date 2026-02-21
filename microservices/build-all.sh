#!/bin/bash
# Build all microservices
set -e

echo "=== Building CRM Microservices ==="

# Build common library first
echo "\n[1/6] Building common-lib..."
cd common-lib && mvn clean install -DskipTests && cd ..

# Build Eureka Server
echo "\n[2/6] Building eureka-server..."
cd eureka-server && mvn clean package -DskipTests && cd ..

# Build API Gateway
echo "\n[3/6] Building api-gateway..."
cd api-gateway && mvn clean package -DskipTests && cd ..

# Build Auth Service
echo "\n[4/6] Building auth-service..."
cd auth-service && mvn clean package -DskipTests && cd ..

# Build Workflow Service
echo "\n[5/6] Building workflow-service..."
cd workflow-service && mvn clean package -DskipTests && cd ..

# Build Integration Service
echo "\n[6/7] Building integration-service..."
cd integration-service && mvn clean package -DskipTests && cd ..

# Build Notification Service
echo "\n[7/7] Building notification-service..."
cd notification-service && mvn clean package -DskipTests && cd ..

echo "\n=== All services built successfully! ==="
echo "Run 'docker-compose up -d' to start all services."
