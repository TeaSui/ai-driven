# Code Patterns Rules

- API responses — Success: { "data": {...}, "meta": {"timestamp": "..."} }, Error: { "error": {"code": "...", "message": "...", "details": []} }
- Error handling: try/catch at boundaries only
- Config: environment variables, no hardcoded values
- Database: repository pattern for data access
- Services: single responsibility, dependency injection
- DTOs: validate at entry points
