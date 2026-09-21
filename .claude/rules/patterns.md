# Code Patterns Rules

- **API response envelope (mandatory for every HTTP-exposed endpoint):**
  - Success: `{ "data": {...}, "meta": {"timestamp": "..."} }`
  - Error: `{ "error": {"code": "...", "message": "...", "details": []} }`
  - Applies to all implementation agents that produce HTTP endpoints: `backend-engineer-subagent`, `aws-infrastructure-subagent` (API Gateway + Lambda), `ai-engineer-subagent` (when exposing model endpoints), `devops-engineer-subagent` (when scaffolding API gateways), and any mobile/frontend BFF produced by those agents. Pre-existing endpoints that diverge should be migrated when touched; do not introduce new shapes.
  - If a third-party contract forces a different shape (e.g., a webhook signed by a vendor), wrap it at the ingress and translate to the envelope internally.
- Error handling: try/catch at boundaries only
- Config: environment variables, no hardcoded values
- Database: repository pattern for data access
- Services: single responsibility, dependency injection
- DTOs: validate at entry points
