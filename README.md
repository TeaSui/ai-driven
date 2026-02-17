# AI-Driven Workflow Automation Platform

A modular, multi-tenant workflow automation platform designed to be easily customized and deployed for different clients.

## Architecture Overview

This project follows a **microservices architecture** organized as a monorepo. Each service is independently buildable, testable, and deployable.

```
┌─────────────────────────────────────────────────────────┐
│                      API Gateway                         │
│              (Routing, Auth, Rate Limiting)               │
└──────┬──────┬──────────┬───────────┬────────────┬────────┘
       │      │          │           │            │
  ┌────▼──┐ ┌─▼────────┐ ┌▼─────────┐ ┌▼──────────┐ ┌▼───────────┐
  │ Auth  │ │ Workflow  │ │Integration│ │Notification│ │  Tenant    │
  │Service│ │  Engine   │ │  Service  │ │  Service   │ │  Service   │
  └───────┘ └──────────┘ └──────────┘ └───────────┘ └────────────┘
       │         │            │            │              │
  ┌────▼─────────▼────────────▼────────────▼──────────────▼───┐
  │                    Shared / Common                         │
  │          (Interfaces, DTOs, Utilities, Config)             │
  └────────────────────────────────────────────────────────────┘
```

## Services

| Service | Port | Description |
|---|---|---|
| `api-gateway` | 3000 | Central entry point, routing, authentication middleware |
| `auth-service` | 3001 | JWT-based authentication, OAuth, tenant-scoped auth |
| `workflow-engine` | 3002 | Core workflow definition, execution, and scheduling |
| `integration-service` | 3003 | Pluggable connectors for third-party tools |
| `notification-service` | 3004 | Multi-channel notifications (email, Slack, webhooks) |
| `tenant-service` | 3005 | Multi-tenant management, configuration, onboarding |
| `shared/common` | — | Shared library (interfaces, DTOs, utilities) |

## Getting Started

### Prerequisites
- Node.js >= 18
- Docker & Docker Compose
- npm >= 9

### Install Dependencies
```bash
npm install
npm run bootstrap
```

### Run All Services (Development)
```bash
docker-compose up
```

### Run a Single Service
```bash
cd services/auth-service
npm install
npm run dev
```

### Run Tests
```bash
# All services
npm test

# Single service
cd services/workflow-engine
npm test
```

## Multi-Tenant Design

Each client (tenant) gets:
- Isolated configuration and data
- Custom workflow definitions
- Selective module activation (only pay for what you use)
- Custom integration connectors

## Adding a New Integration Connector

See `services/integration-service/src/connectors/` for examples. Each connector implements the `IConnector` interface from `shared/common`.

## Deployment

Each service has its own `Dockerfile` and can be deployed independently. Use `docker-compose.yml` for local development or orchestrate with Kubernetes for production.

## License

Proprietary — All rights reserved.
