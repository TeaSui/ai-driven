# Microservices Architecture - CRM-88

## Overview

This directory contains the microservices adaptation of the AI-Driven Workflow Automation platform.
Each service is independently deployable, testable, and scalable.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        API Gateway                          │
│                    (Port 8080)                              │
└──────────┬──────────┬──────────┬──────────┬────────────────┘
           │          │          │          │
    ┌──────▼──┐ ┌────▼────┐ ┌──▼──────┐ ┌▼──────────┐
    │  Auth   │ │Workflow │ │Integration│ │Notification│
    │ Service │ │ Service │ │ Service  │ │  Service  │
    │  :8081  │ │  :8082  │ │  :8083  │ │   :8084   │
    └─────────┘ └─────────┘ └─────────┘ └───────────┘
           │          │          │          │
    ┌──────▼──────────▼──────────▼──────────▼──────┐
    │              Message Bus (Redis/RabbitMQ)      │
    └───────────────────────────────────────────────┘
           │          │          │          │
    ┌──────▼──┐ ┌────▼────┐ ┌──▼──────┐ ┌▼──────────┐
    │  Auth   │ │Workflow │ │Integration│ │Notification│
    │   DB    │ │   DB    │ │   DB    │ │    DB     │
    └─────────┘ └─────────┘ └─────────┘ └───────────┘
```

## Services

| Service | Port | Responsibility |
|---------|------|----------------|
| API Gateway | 8080 | Request routing, rate limiting, auth validation |
| Auth Service | 8081 | Authentication, authorization, JWT management |
| Workflow Service | 8082 | Workflow definitions, execution engine |
| Integration Service | 8083 | Third-party tool connectors (Slack, Jira, etc.) |
| Notification Service | 8084 | Email, SMS, push notifications |
| Shared Library | - | Common DTOs, utilities, event contracts |

## Quick Start

```bash
# Start all services
docker-compose up -d

# Start individual service
docker-compose up -d auth-service

# View logs
docker-compose logs -f workflow-service

# Run tests for all services
./scripts/test-all.sh
```

## Environment Variables

Copy `.env.example` to `.env` and configure:

```bash
cp .env.example .env
```

## Service Communication

- **Synchronous**: REST over HTTP (via API Gateway)
- **Asynchronous**: Event-driven via Redis Pub/Sub or RabbitMQ
- **Service Discovery**: Docker DNS / Kubernetes Service

## Adding a New Integration

1. Add connector in `integration-service/src/connectors/`
2. Register events in `shared/src/events/`
3. Update API Gateway routes if needed
4. Add environment variables to `.env.example`

## Client Customization

Each client deployment can:
- Enable/disable specific services via feature flags
- Configure custom integrations per tenant
- Scale individual services independently
- Use client-specific database instances
