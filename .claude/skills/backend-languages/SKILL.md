---
name: backend-languages
description: >
  FOR BACKEND-ENGINEER-SUBAGENT USE ONLY. Do not load in the main session.
  Routes to the correct language reference based on project triggers.
  Covers Go, Spring Boot (Java), Python, and Rust backend patterns.
type: reference
---

# Backend Language References

This skill is scoped exclusively to `backend-engineer-subagent`. It routes to the appropriate language reference file based on detected triggers. Load only the reference that matches your current task.

## Language Router

| Language | Triggers | Reference |
|----------|----------|-----------|
| **Go** | `.go` files, `go.mod`, `goroutine`, `channel`, `errgroup`, Go modules | `golang.md` |
| **Spring Boot** | `@SpringBootApplication`, `@RestController`, `build.gradle`, `pom.xml`, Spring MVC, JPA, Hibernate | `spring-boot.md` |
| **Python** | `.py` files, `pyproject.toml`, `requirements.txt`, FastAPI, Starlette, `asyncio`, Pydantic | `python.md` |
| **Rust** | `.rs` files, `Cargo.toml`, `cargo workspace`, `tokio`, `axum`, `actix`, `thiserror`, `anyhow` | `rust.md` |

## How to use

1. Detect the language from file extensions, build manifests, or imports.
2. Invoke `Skill` with the matching reference filename (e.g., `backend-languages/golang`).
3. Apply patterns from that reference. Do not load multiple language references unless the project is genuinely polyglot.

## Shared conventions (all languages)

- REST response envelope: `{ "data": {...}, "meta": { "timestamp": "..." } }` / `{ "error": { "code": "...", "message": "...", "details": [] } }`
- Config via environment variables only — no hardcoded values
- Repository pattern for data access; services own business logic
- DTOs validated at entry points; never expose raw persistence models in API responses
- Mock only external HTTP APIs in tests; use real or in-memory DB for query tests
