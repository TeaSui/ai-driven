---
name: backend-languages-rust
description: >
  FOR BACKEND-ENGINEER-SUBAGENT USE ONLY.
  Idiomatic Rust (stable, 2024 edition) backend patterns: cargo workspaces, Result+thiserror/anyhow,
  trait design, table-driven tests with rstest, tokio async, axum handler patterns, error propagation.
type: reference
---

# Rust Backend Patterns

## When to use

Triggered by: `.rs` files, `Cargo.toml`, `tokio`, `axum`, `actix-web`, `thiserror`, `anyhow`, cargo workspace.

## Project layout

```
myservice/
├── Cargo.toml                   # Workspace manifest [workspace] members = [...]
├── Cargo.lock                   # Always committed for binaries
├── crates/
│   ├── api/                     # axum router, handlers, extractors, response types
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.rs
│   │       ├── router.rs        # Router construction, state injection
│   │       ├── handlers/        # One module per resource (orders.rs, users.rs)
│   │       └── error.rs         # AppError implementing IntoResponse
│   ├── service/                 # Business logic, no HTTP concerns
│   │   └── src/lib.rs
│   ├── repository/              # DB access via sqlx or diesel
│   │   └── src/lib.rs
│   └── domain/                  # Shared types, traits, error enums
│       └── src/lib.rs
└── src/
    └── main.rs                  # Wire tokio runtime, config, router, server
```

**Rules:**
- Workspace crates enforce dependency boundaries at the compiler level.
- `domain` crate has no external I/O dependencies — it defines traits, types, and errors.
- `api` crate owns `AppError` which implements `axum::response::IntoResponse`.
- `Cargo.lock` is always committed for application crates (not library crates).

## Error handling

Use `thiserror` for library/domain errors; `anyhow` for application-level glue code.

```rust
// domain/src/error.rs
use thiserror::Error;

#[derive(Debug, Error)]
pub enum DomainError {
    #[error("order {0} not found")]
    NotFound(String),
    #[error("insufficient stock: requested {requested}, available {available}")]
    InsufficientStock { requested: u32, available: u32 },
    #[error("validation: {0}")]
    Validation(String),
}
```

```rust
// api/src/error.rs — convert domain errors to HTTP responses
use axum::{http::StatusCode, response::{IntoResponse, Response}, Json};
use serde_json::json;
use crate::domain::error::DomainError;

pub enum AppError {
    Domain(DomainError),
    Internal(anyhow::Error),
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, code, message) = match &self {
            AppError::Domain(DomainError::NotFound(id)) =>
                (StatusCode::NOT_FOUND, "NOT_FOUND", format!("Not found: {id}")),
            AppError::Domain(DomainError::InsufficientStock { .. }) =>
                (StatusCode::UNPROCESSABLE_ENTITY, "INSUFFICIENT_STOCK", self.to_string()),
            AppError::Domain(DomainError::Validation(msg)) =>
                (StatusCode::BAD_REQUEST, "VALIDATION_ERROR", msg.clone()),
            AppError::Internal(_) =>
                (StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error".into()),
        };
        let body = json!({"error": {"code": code, "message": message, "details": []}});
        (status, Json(body)).into_response()
    }
}

// Blanket From conversions
impl From<DomainError> for AppError {
    fn from(e: DomainError) -> Self { AppError::Domain(e) }
}

impl From<anyhow::Error> for AppError {
    fn from(e: anyhow::Error) -> Self { AppError::Internal(e) }
}
```

**Rules:**
- Handler return type: `Result<impl IntoResponse, AppError>` — the `?` operator propagates via `From`.
- Never `unwrap()` or `expect()` in production paths; reserve for compile-time invariants or tests.
- Never log sensitive data (tokens, PII) in error contexts.
- Use `tracing::error!` (not `eprintln!`) for server-side error logging before converting to response.

## Testing

Use `#[cfg(test)]` modules for unit tests; `rstest` for table-driven parametrized tests.

```rust
// service/src/order.rs
#[cfg(test)]
mod tests {
    use super::*;
    use rstest::rstest;

    #[rstest]
    #[case(vec![LineItem { price: 100, qty: 2 }], 0.0, 200.0)]
    #[case(vec![LineItem { price: 100, qty: 1 }, LineItem { price: 50, qty: 3 }], 0.1, 225.0)]
    #[case(vec![], 0.0, 0.0)]  // empty cart
    fn test_calculate_total(
        #[case] items: Vec<LineItem>,
        #[case] discount: f64,
        #[case] expected: f64,
    ) {
        let result = calculate_total(&items, discount);
        assert!((result - expected).abs() < f64::EPSILON);
    }

    #[tokio::test]
    async fn create_order_returns_not_found_for_missing_product() {
        let repo = MockOrderRepo::new(); // or test double via trait
        let svc = OrderService::new(repo);
        let err = svc.create(CreateOrderRequest { product_id: 999, qty: 1 }).await.unwrap_err();
        assert!(matches!(err, DomainError::NotFound(_)));
    }
}
```

**Rules:**
- Integration tests in `tests/` directory at crate root; use `tokio::test` for async.
- Use `sqlx::test` macro (with test DB) for repository tests — do not mock the DB layer.
- Coverage: `cargo tarpaulin --out Lcov` or `cargo llvm-cov`; target ≥ 80%.

## Concurrency

```rust
// Parallel async tasks with tokio::join!
use tokio::join;

async fn enrich_order(order: &Order) -> Result<EnrichedOrder, AppError> {
    let (user_result, product_result) = join!(
        user_client.get(order.user_id),
        product_client.get(order.product_id),
    );
    let user = user_result.context("fetch user")?;
    let product = product_result.context("fetch product")?;
    Ok(EnrichedOrder { order, user, product })
}

// Fan-out with JoinSet for dynamic number of tasks
use tokio::task::JoinSet;

async fn process_batch(ids: Vec<u64>) -> Vec<Result<Output, AppError>> {
    let mut set = JoinSet::new();
    for id in ids {
        set.spawn(async move { process_one(id).await });
    }
    let mut results = Vec::new();
    while let Some(res) = set.join_next().await {
        results.push(res.expect("task panicked"));
    }
    results
}
```

**Rules:**
- `tokio::spawn` for fire-and-forget; `JoinSet` when you need to collect results.
- Never block the async runtime: avoid `std::thread::sleep`, blocking file I/O, or CPU-heavy work without `spawn_blocking`.
- Use `tokio::sync::Mutex` (not `std::sync::Mutex`) when the guard must be held across `.await` points.

## HTTP handler patterns (axum)

```rust
// api/src/handlers/orders.rs
use axum::{extract::{Path, State}, Json};
use serde::{Deserialize, Serialize};
use crate::{AppState, error::AppError};

#[derive(Deserialize)]
pub struct CreateOrderBody {
    pub product_id: u64,
    pub qty: u32,
}

#[derive(Serialize)]
pub struct OrderResponse {
    pub id: u64,
    pub status: String,
}

pub async fn create_order(
    State(state): State<AppState>,
    Json(body): Json<CreateOrderBody>,
) -> Result<(axum::http::StatusCode, Json<serde_json::Value>), AppError> {
    let order = state.order_svc.create(body.product_id, body.qty).await?;
    let resp = serde_json::json!({"data": {"id": order.id, "status": order.status}});
    Ok((axum::http::StatusCode::CREATED, Json(resp)))
}

// router.rs
pub fn build_router(state: AppState) -> axum::Router {
    axum::Router::new()
        .route("/orders", axum::routing::post(create_order))
        .route("/orders/:id", axum::routing::get(get_order))
        .with_state(state)
        .layer(tower_http::trace::TraceLayer::new_for_http())
}
```

**Rules:**
- Handlers are `async fn`; return `Result<impl IntoResponse, AppError>`.
- Use `State(state): State<AppState>` for shared application state (DB pool, service handles).
- `Json(body)` rejects malformed JSON with 422 automatically; for custom rejection handling see `JsonRejection`.
- Add middleware via `.layer()` on the `Router`; use `ServiceBuilder` to stack multiple layers.

## Dependency management

```toml
# Cargo.toml (workspace root)
[workspace]
members = ["crates/api", "crates/service", "crates/repository", "crates/domain"]
resolver = "2"

[workspace.dependencies]
axum = { version = "0.7", features = ["macros"] }
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
thiserror = "1"
anyhow = "1"
tracing = "1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
sqlx = { version = "0.7", features = ["postgres", "runtime-tokio-rustls", "macros"] }
tower-http = { version = "0.5", features = ["trace", "cors"] }
rstest = "0.19"

# Edition
[workspace.package]
edition = "2021"   # Use 2024 once stable in your toolchain
```

- Define all shared dependency versions in `[workspace.dependencies]`; crates inherit with `dep = { workspace = true }`.
- Run `cargo clippy -- -D warnings` and `cargo fmt --check` in CI.
- Use `cargo audit` to check for known CVEs in dependencies.

## Common pitfalls

1. **`unwrap()` in request handlers** — panics are caught by axum as 500 but lose context. Use `?` with `From` conversions.
2. **Holding `std::sync::Mutex` across `.await`** — deadlock risk; use `tokio::sync::Mutex`.
3. **Cloning large state** — `AppState` should wrap `Arc<Inner>` so `.clone()` is cheap (pointer copy).
4. **Missing `Send` bound on spawned futures** — `tokio::spawn` requires `Future: Send`. Avoid `Rc`, `RefCell`, non-`Send` guards across `.await`.
5. **`serde_json::Value` everywhere** — define typed structs with `#[derive(Serialize, Deserialize)]`. Untyped JSON loses compile-time safety.
6. **Ignoring `tracing` spans** — use `#[tracing::instrument]` on service methods for distributed traces; do not use `println!` in production.
7. **Forgetting `Cargo.lock`** — application binaries must commit the lock file for reproducible builds.
