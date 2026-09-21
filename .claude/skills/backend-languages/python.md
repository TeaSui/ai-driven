---
name: backend-languages-python
description: >
  FOR BACKEND-ENGINEER-SUBAGENT USE ONLY.
  Idiomatic Python 3.12+ backend patterns: src layout, pyproject.toml, typing, pytest,
  asyncio, FastAPI/Starlette handlers, exception hierarchies, dependency injection, uv/poetry.
type: reference
---

# Python Backend Patterns

## When to use

Triggered by: `.py` files, `pyproject.toml`, `requirements.txt`, FastAPI, Starlette, Pydantic, `asyncio`.

## Project layout

```
myservice/
├── src/
│   └── myservice/
│       ├── __init__.py
│       ├── main.py              # App factory, lifespan, router registration
│       ├── config.py            # Settings via pydantic-settings
│       ├── api/
│       │   ├── routers/         # One router per resource (orders.py, users.py)
│       │   └── deps.py          # Shared FastAPI dependencies
│       ├── service/             # Business logic, no HTTP concerns
│       ├── repository/          # Data access (SQLAlchemy / async db calls)
│       ├── models/              # ORM models (SQLAlchemy declarative)
│       ├── schemas/             # Pydantic request/response schemas
│       └── exceptions.py        # Custom exception hierarchy
├── tests/
│   ├── conftest.py              # Fixtures: test client, DB session, fakes
│   ├── unit/
│   └── integration/
├── pyproject.toml
└── Makefile
```

**Rules:**
- `src/` layout prevents accidental imports from the project root.
- `schemas/` ≠ `models/` — ORM models never leave the repository layer as API responses.
- `config.py` uses `pydantic-settings` with a `Settings` class; one singleton via `lru_cache`.

## Error handling

Define a custom exception hierarchy; handle centrally via FastAPI exception handlers.

```python
# exceptions.py
class AppError(Exception):
    status_code: int = 500
    code: str = "INTERNAL_ERROR"
    def __init__(self, message: str):
        self.message = message
        super().__init__(message)

class NotFoundError(AppError):
    status_code = 404
    code = "NOT_FOUND"

class ValidationError(AppError):
    status_code = 422
    code = "VALIDATION_ERROR"
    def __init__(self, message: str, details: list[dict] | None = None):
        super().__init__(message)
        self.details = details or []
```

```python
# main.py — register handlers once
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError

app = FastAPI()

@app.exception_handler(AppError)
async def app_error_handler(request: Request, exc: AppError) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status_code,
        content={"error": {"code": exc.code, "message": exc.message, "details": getattr(exc, "details", [])}},
    )

@app.exception_handler(RequestValidationError)
async def validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    details = [{"field": ".".join(str(l) for l in e["loc"]), "message": e["msg"]} for e in exc.errors()]
    return JSONResponse(
        status_code=422,
        content={"error": {"code": "VALIDATION_ERROR", "message": "Invalid input", "details": details}},
    )
```

**Rules:**
- Raise `AppError` subclasses in service layer; never catch-and-swallow in individual routes.
- Never expose internal tracebacks or ORM details in error responses.
- Log the full exception server-side before returning a sanitized response.

## Testing

Use `pytest` with fixtures; prefer table-style parametrize over copy-pasted test functions.

```python
# conftest.py
import pytest
from httpx import AsyncClient, ASGITransport
from myservice.main import app

@pytest.fixture
async def client():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c
```

```python
# test_orders.py
import pytest

@pytest.mark.parametrize("payload,expected_status,expected_code", [
    ({"product_id": 1, "qty": 2}, 201, None),
    ({"qty": 2}, 422, "VALIDATION_ERROR"),       # missing product_id
    ({"product_id": 99, "qty": 2}, 404, "NOT_FOUND"),
])
async def test_create_order(client, payload, expected_status, expected_code):
    resp = await client.post("/api/orders", json=payload)
    assert resp.status_code == expected_status
    if expected_code:
        assert resp.json()["error"]["code"] == expected_code
```

**Rules:**
- Use `pytest-asyncio` for async tests; set `asyncio_mode = "auto"` in `pyproject.toml`.
- Integration tests use a real DB (Testcontainers or in-process SQLite where appropriate).
- Coverage target ≥ 80%; run `pytest --cov=src/myservice`.

## Concurrency

```python
# Async handler — non-blocking I/O
@router.get("/orders/{order_id}")
async def get_order(order_id: int, svc: Annotated[OrderService, Depends(get_order_service)]) -> OrderResponse:
    order = await svc.get_by_id(order_id)
    if order is None:
        raise NotFoundError(f"Order {order_id} not found")
    return OrderResponse.model_validate(order)

# Parallel async tasks
import asyncio

async def enrich_order(order_id: int) -> dict:
    user_task = asyncio.create_task(user_client.get(order.user_id))
    product_task = asyncio.create_task(product_client.get(order.product_id))
    user, product = await asyncio.gather(user_task, product_task)
    return {"user": user, "product": product}
```

**Rules:**
- `async def` for all I/O-bound handlers. Use `def` (sync) only for CPU-bound work run via `asyncio.run_in_executor`.
- Never call blocking stdlib functions (`time.sleep`, `open()`) inside `async def` — they block the event loop. Use `asyncio.sleep` and `aiofiles`.
- Use `asyncio.gather` for parallel awaitable calls; prefer `asyncio.TaskGroup` (Python 3.11+) for structured concurrency.

## HTTP handler patterns

```python
# api/routers/orders.py
from typing import Annotated
from fastapi import APIRouter, Depends, status
from myservice.api.deps import get_order_service
from myservice.schemas.order import CreateOrderRequest, OrderResponse
from myservice.service.order import OrderService

router = APIRouter(prefix="/orders", tags=["orders"])

@router.post("/", response_model=OrderResponse, status_code=status.HTTP_201_CREATED)
async def create_order(
    body: CreateOrderRequest,
    svc: Annotated[OrderService, Depends(get_order_service)],
) -> OrderResponse:
    return await svc.create(body)

@router.get("/{order_id}", response_model=OrderResponse)
async def get_order(order_id: int, svc: Annotated[OrderService, Depends(get_order_service)]) -> OrderResponse:
    order = await svc.get_by_id(order_id)
    if order is None:
        raise NotFoundError(f"Order {order_id} not found")
    return order
```

**Rules:**
- Routers are thin — validate with Pydantic schemas, call service, return response model.
- Always specify `response_model` to prevent leaking internal fields.
- Use `Annotated[T, Depends(...)]` (Python 3.9+ style) for dependency injection.

## Dependency management

```toml
# pyproject.toml (uv or poetry)
[project]
name = "myservice"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = [
    "fastapi>=0.115",
    "pydantic>=2.0",
    "pydantic-settings>=2.0",
    "sqlalchemy[asyncio]>=2.0",
    "asyncpg>=0.29",
    "uvicorn[standard]>=0.30",
]

[project.optional-dependencies]
dev = ["pytest", "pytest-asyncio", "httpx", "pytest-cov", "mypy", "ruff"]

[tool.pytest.ini_options]
asyncio_mode = "auto"

[tool.mypy]
strict = true
```

- Prefer `uv` for speed (`uv sync`, `uv add <pkg>`). Fall back to `poetry` if project already uses it.
- Pin transitive deps via `uv.lock` / `poetry.lock` — always commit the lock file.
- Run `mypy --strict` and `ruff check` in CI.

## Common pitfalls

1. **Mutable default arguments** — `def f(items=[])` shares the list across calls. Use `None` + `items = items or []`.
2. **Blocking in async handlers** — `requests.get()` inside `async def` blocks the event loop. Use `httpx.AsyncClient`.
3. **Missing `await`** — calling an `async def` without `await` returns a coroutine object silently. Enable `asyncio_mode = "auto"` and use `anyio` or `pytest-asyncio`.
4. **ORM model as response** — SQLAlchemy model leaks lazy-loaded fields. Always `.model_validate(orm_obj)` into a Pydantic schema.
5. **`from __future__ import annotations` on Pydantic v2** — can break runtime field resolution. Prefer explicit type annotations.
6. **`settings` as module-level singleton without `lru_cache`** — re-reads env vars on every import. Wrap `get_settings()` with `@lru_cache`.
7. **Catching `Exception` bare** — masks bugs. Catch specific exception types; re-raise or convert to `AppError`.
