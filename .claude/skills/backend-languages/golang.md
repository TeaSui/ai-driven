---
name: backend-languages-golang
description: >
  FOR BACKEND-ENGINEER-SUBAGENT USE ONLY.
  Go backend patterns: standard layout (cmd/internal/pkg), error handling (%w wrapping, sentinel errors),
  consumer-side interfaces, table-driven tests, errgroup, context cancellation, HTTP handler/middleware patterns.
type: reference
---

# Go Backend Patterns

## When to use

Triggered by: `.go` files, `go.mod`, `goroutine`, `channel`, `errgroup`, Go modules, Go services.

## Project layout

```
myservice/
├── cmd/
│   └── myservice/
│       └── main.go              # Parse config, wire deps, start server. No business logic.
├── internal/                    # Compiler-enforced private to this module
│   ├── handler/                 # HTTP handlers
│   ├── service/                 # Business logic
│   ├── repository/              # Data access
│   ├── model/                   # Domain types (structs, enums)
│   ├── client/                  # External service clients
│   └── middleware/              # HTTP middleware
├── pkg/                         # Public libraries (only if intentionally shared across repos)
├── go.mod
├── go.sum
└── Makefile
```

**Rules:**
- `internal/` by default. Other modules cannot import it.
- `pkg/` only when you want other repos to import it. Most services need zero `pkg/`.
- `cmd/` = one directory per binary; `main.go` wires everything.
- No `utils/` or `helpers/` packages. If you can't name it, the code belongs somewhere else.

## Error handling

```go
// Wrap errors with context using %w
func (r *OrderRepo) FindByID(ctx context.Context, id string) (*Order, error) {
    row := r.db.QueryRowContext(ctx, "SELECT ... WHERE id = $1", id)
    var o Order
    if err := row.Scan(&o.ID, &o.Status, &o.Total); err != nil {
        if errors.Is(err, sql.ErrNoRows) {
            return nil, ErrOrderNotFound // sentinel — do not wrap
        }
        return nil, fmt.Errorf("query order %s: %w", id, err)
    }
    return &o, nil
}

// Sentinel errors
var (
    ErrOrderNotFound     = errors.New("order not found")
    ErrInsufficientStock = errors.New("insufficient stock")
)

// Error types (when error carries structured data)
type ValidationError struct {
    Field   string
    Message string
}

func (e *ValidationError) Error() string {
    return fmt.Sprintf("validation: %s — %s", e.Field, e.Message)
}

// Checking errors
if errors.Is(err, ErrOrderNotFound) { /* handle */ }

var valErr *ValidationError
if errors.As(err, &valErr) { /* access valErr.Field */ }
```

**Rules:**
1. Handle or return — never ignore (`_, _ = doThing()` is almost always a bug).
2. Wrap with `%w`; the caller needs to know *what* failed.
3. Don't wrap twice — if a function already wraps, the caller adds its own context.
4. Sentinel errors for expected conditions; error types for errors carrying data.
5. Never `panic` in library code; only for unrecoverable programmer bugs.

## Testing

```go
func TestOrderService_CalculateTotal(t *testing.T) {
    tests := []struct {
        name     string
        items    []LineItem
        discount float64
        want     float64
        wantErr  error
    }{
        {name: "single item no discount", items: []LineItem{{Price: 100, Qty: 2}}, discount: 0, want: 200},
        {name: "multiple items with discount",
            items: []LineItem{{Price: 100, Qty: 1}, {Price: 50, Qty: 3}}, discount: 0.1, want: 225},
        {name: "empty items returns error", items: nil, wantErr: ErrEmptyOrder},
    }

    for _, tt := range tests {
        t.Run(tt.name, func(t *testing.T) {
            got, err := NewOrderService().CalculateTotal(tt.items, tt.discount)
            if !errors.Is(err, tt.wantErr) {
                t.Fatalf("error = %v, wantErr = %v", err, tt.wantErr)
            }
            if got != tt.want {
                t.Errorf("got %v, want %v", got, tt.want)
            }
        })
    }
}
```

**Rules:**
- `foo_test.go` next to `foo.go`. Same package for white-box; `_test` suffix for black-box.
- Use `t.Helper()` in test helpers so failures report the caller's line.
- `t.Parallel()` for independent tests.
- Use `testify/assert` or `testify/require` if the project already uses it; otherwise stdlib is fine.

## Concurrency

```go
// errgroup — parallel work with error collection and context cancellation
import "golang.org/x/sync/errgroup"

func (s *OrderService) EnrichOrder(ctx context.Context, order *Order) error {
    g, ctx := errgroup.WithContext(ctx)

    g.Go(func() error {
        user, err := s.userClient.GetUser(ctx, order.UserID)
        if err != nil {
            return fmt.Errorf("fetch user: %w", err)
        }
        order.UserName = user.Name
        return nil
    })

    g.Go(func() error {
        product, err := s.productClient.GetProduct(ctx, order.ProductID)
        if err != nil {
            return fmt.Errorf("fetch product: %w", err)
        }
        order.ProductName = product.Name
        return nil
    })

    return g.Wait()
}
```

**Context rules:**
1. First parameter, always: `func DoThing(ctx context.Context, ...) error`.
2. Pass it through every function that does I/O.
3. Never store `context.Context` in structs — it is per-request.
4. Check cancellation in long loops with `select { case <-ctx.Done(): return ctx.Err() default: }`.

**Goroutine leak prevention:**
```go
// RIGHT — buffered channel + context exit
func doWorkAsync(ctx context.Context) <-chan Result {
    ch := make(chan Result, 1)
    go func() {
        defer close(ch)
        result := doWork()
        select {
        case ch <- result:
        case <-ctx.Done():
        }
    }()
    return ch
}
```

## HTTP handler patterns

```go
// Handler — dependencies via closure, returns http.HandlerFunc
func NewCreateOrderHandler(svc *service.OrderService) http.HandlerFunc {
    return func(w http.ResponseWriter, r *http.Request) {
        var req CreateOrderRequest
        if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
            writeError(w, http.StatusBadRequest, "INVALID_BODY", "invalid request body")
            return
        }
        order, err := svc.CreateOrder(r.Context(), req)
        if err != nil {
            handleServiceError(w, err)
            return
        }
        writeJSON(w, http.StatusCreated, map[string]any{"data": order})
    }
}

// Middleware — wraps http.Handler, returns http.Handler
func LoggingMiddleware(logger *slog.Logger) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            start := time.Now()
            ww := &responseWriter{ResponseWriter: w, status: http.StatusOK}
            next.ServeHTTP(ww, r) // MUST call next
            logger.Info("request", "method", r.Method, "path", r.URL.Path,
                "status", ww.status, "duration_ms", time.Since(start).Milliseconds())
        })
    }
}
```

## Dependency management

```bash
go mod init github.com/org/myservice
go mod tidy       # add missing, remove unused
go mod vendor     # vendor deps (if required by CI)
```

- Pin major versions: `require github.com/foo/bar/v2 v2.3.1`
- Run `go mod tidy` before committing
- Always commit `go.sum` for integrity verification

## Common pitfalls

1. **Goroutine leak** — every goroutine needs an exit path. Use `context.Context` cancellation.
2. **Naked `return nil, err`** — wrap: `fmt.Errorf("doing X: %w", err)` before returning.
3. **Interface in producer package** — define interfaces in the consumer package, not where they're implemented.
4. **Loop variable capture** — `for _, v := range items { go func() { use(v) }() }` captures loop var. Pass as parameter: `go func(v Item) { use(v) }(v)`. (Fixed in Go 1.22+ but be explicit.)
5. **`init()` functions** — avoid; they make testing hard and create hidden dependencies. Wire in `main()`.
6. **`panic` for runtime errors** — panic only for programmer bugs (nil dereference guards), not expected conditions.
7. **Ignoring `context.Context`** — not threading context through layers means no cancellation, timeout, or tracing.
