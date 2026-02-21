# Microservices Architecture - CRM-88

## Overview

This document describes the microservices adaptation of the workflow automation platform. The monolithic application has been decomposed into isolated, independently deployable modules that can be mixed and matched for different client needs.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        API Gateway                          │
│                    (Port 8080)                              │
└──────────┬──────────┬──────────┬──────────┬────────────────┘
           │          │          │          │
    ┌──────▼──┐ ┌─────▼───┐ ┌───▼─────┐ ┌──▼──────────┐
    │  Auth   │ │Workflow │ │Integration│ │Notification │
    │Service  │ │Service  │ │ Service  │ │  Service    │
    │ :8081   │ │  :8082  │ │  :8083  │ │   :8084     │
    └──────┬──┘ └─────┬───┘ └───┬─────┘ └──┬──────────┘
           │          │          │           │
    ┌──────▼──────────▼──────────▼───────────▼──────────┐
    │              Message Broker (RabbitMQ)              │
    └─────────────────────────────────────────────────────┘
           │          │          │           │
    ┌──────▼──┐ ┌─────▼───┐ ┌───▼─────┐ ┌──▼──────────┐
    │  Auth   │ │Workflow │ │Integration│ │Notification │
    │   DB    │ │   DB    │ │   DB    │ │    DB       │
    └─────────┘ └─────────┘ └─────────┘ └─────────────┘
```

## Services

### 1. API Gateway (Port 8080)
- Single entry point for all client requests
- Request routing and load balancing
- Authentication token validation
- Rate limiting

### 2. Auth Service (Port 8081)
- User authentication and authorization
- JWT token management
- Role-based access control
- Multi-tenant support

### 3. Workflow Service (Port 8082)
- Workflow definition and management
- Workflow execution engine
- Step orchestration
- State management

### 4. Integration Service (Port 8083)
- Third-party tool connectors
- Webhook management
- Data transformation
- Plugin system for custom integrations

### 5. Notification Service (Port 8084)
- Email notifications
- In-app notifications
- Webhook callbacks
- Notification templates

## Getting Started

### Prerequisites
- Docker & Docker Compose
- Java 17+
- Maven 3.8+

### Running All Services

```bash
# Start infrastructure
docker-compose -f docker-compose.infrastructure.yml up -d

# Build all services
mvn clean package -DskipTests

# Start all services
docker-compose up -d
```

### Running Individual Services

```bash
# Start only the workflow service
cd workflow-service
mvn spring-boot:run
```

## Client Integration Guide

Each client can choose which modules to deploy:

| Use Case | Required Services |
|----------|------------------|
| Basic Automation | Auth + Workflow |
| Full Platform | All Services |
| Custom Integration | Auth + Workflow + Integration |
| Notification Only | Auth + Notification |

## Configuration

Each service is configured via environment variables. See each service's `application.yml` for available options.

## Inter-Service Communication

- **Synchronous**: REST APIs via API Gateway
- **Asynchronous**: RabbitMQ message broker for event-driven communication
- **Service Discovery**: Spring Cloud Eureka
