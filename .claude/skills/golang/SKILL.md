---
name: golang
description: |
  Go project patterns: standard layout (cmd/internal/pkg), error handling idioms (%w wrapping, sentinel errors),
  interface design (consumer-side), table-driven tests, Go modules, concurrency (errgroup, context cancellation),
  HTTP handler/middleware patterns, and struct design.
  Use when: writing Go code, creating Go services, designing Go interfaces, writing Go tests, or debugging Go errors.
  Triggers on: Go, Golang, .go files, go.mod, goroutine, channel, Go modules.
---

# Go Patterns

## Project Layout

```
myservice/
├── cmd/
│   └── myservice/
│       └── main.go              # Entrypoint — parse config, wire dependencies, start server
├── internal/                    # Private to this module (compiler-enforced)
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
- `internal/` = private to this module. Other modules cannot import it. Use by default.
- `pkg/` = intentionally public. Only create when you want other repos to import it. Most services need zero `pkg/`.
- `cmd/` = one directory per binary. `main.go` wires everything; no business logic here.
- No `utils/` or `helpers/` packages. If you can't name it, the code belongs somewhere else.

## Error Handling

### The Rules
1. **Handle or return — never ignore.** `result, _ := doThing()` is almost always a bug.
2. **Wrap with context using `%w`.** The caller needs to know *what failed*, not just *that something failed*.
3. **Don't wrap twice.** If a function already wraps, the caller adds its own context, not a duplicate.
4. **Sentinel errors for expected conditions.** Use `errors.Is()` to check.
5. **Error types for errors carrying data.** Use `errors.As()` to extract.
6. **Never panic in library code.** Panic is for truly unrecoverable states (programmer bugs, not runtime errors).

```go
// Wrapping errors with context
func (r *OrderRepo) FindByID(ctx context.Context, id string) (*Order, error) {
    row := r.db.QueryRowContext(ctx, "SELECT ... WHERE id = $1", id)
    var o Order
    if err := row.Scan(&o.ID, &o.Status, &o.Total); err != nil {
        if errors.Is(err, sql.ErrNoRows) {
            return nil, ErrOrderNotFound // sentinel — don't wrap, it IS the error
        }
        return nil, fmt.Errorf("query order %s: %w", id, err) // wrap with context
    }
    return &o, nil
}

// Sentinel errors
var (
    ErrOrderNotFound = errors.New("order not found")
    ErrInsufficientStock = errors.New("insufficient stock")
)

// Error types (when error carries data)
type ValidationError struct {
    Field   string
    Message string
}

func (e *ValidationError) Error() string {
    return fmt.Sprintf("validation: %s — %s", e.Field, e.Message)
}

// Checking errors
if errors.Is(err, ErrOrderNotFound) { /* handle not found */ }

var valErr *ValidationError
if errors.As(err, &valErr) { /* access valErr.Field */ }
```

## Interface Design — Consumer-Side

**Define interfaces where they are USED, not where they are implemented.**

```go
// WRONG — Java-style: define interface in the producer package
// package repository
// type OrderRepository interface { ... }
// type orderRepositoryImpl struct { ... }

// RIGHT — Go-style: define interface in the consumer package
// package service
type OrderRepository interface {
    FindByID(ctx context.Context, id string) (*model.Order, error)
    Save(ctx context.Context, order *model.Order) error
}

type OrderService struct {
    repo OrderRepository // accepts the interface
}

// package repository — just exports the concrete struct
type PostgresOrderRepo struct { db *sql.DB }

func (r *PostgresOrderRepo) FindByID(ctx context.Context, id string) (*model.Order, error) { ... }
func (r *PostgresOrderRepo) Save(ctx context.Context, order *model.Order) error { ... }
```

**Why:** Small, focused interfaces. The consumer declares exactly what it needs. The producer doesn't need to know about or import the interface. Go interfaces are satisfied implicitly.

**Keep interfaces small.** 1-3 methods. If an interface has 10 methods, split it. The `io.Reader` philosophy.

## Table-Driven Tests

```go
func TestOrderService_CalculateTotal(t *testing.T) {
    tests := []struct {
        name     string
        items    []LineItem
        discount float64
        want     float64
        wantErr  error
    }{
        {
            name:     "single item no discount",
            items:    []LineItem{{Price: 100, Qty: 2}},
            discount: 0,
            want:     200,
        },
        {
            name:     "multiple items with discount",
            items:    []LineItem{{Price: 100, Qty: 1}, {Price: 50, Qty: 3}},
            discount: 0.1,
            want:     225, // (100 + 150) * 0.9
        },
        {
            name:     "empty items returns error",
            items:    nil,
            wantErr:  ErrEmptyOrder,
        },
    }

    for _, tt := range tests {
        t.Run(tt.name, func(t *testing.T) {
            svc := NewOrderService()
            got, err := svc.CalculateTotal(tt.items, tt.discount)

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

**Test file conventions:**
- `foo_test.go` next to `foo.go` (same package for white-box, `_test` suffix for black-box)
- Use `t.Helper()` in test helpers so failures report the caller's line
- Use `testify/assert` or `testify/require` if the project already uses it; otherwise, stdlib is fine
- Use `t.Parallel()` for independent tests

## HTTP Handler & Middleware

```go
// Handler — takes dependencies via closure, returns http.HandlerFunc
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

// Middleware — wraps an http.Handler, returns an http.Handler
func LoggingMiddleware(logger *slog.Logger) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            start := time.Now()
            // Wrap ResponseWriter to capture status code
            ww := &responseWriter{ResponseWriter: w, status: http.StatusOK}

            next.ServeHTTP(ww, r) // MUST call next — forgetting this is a common bug

            logger.Info("request",
                "method", r.Method,
                "path", r.URL.Path,
                "status", ww.status,
                "duration_ms", time.Since(start).Milliseconds(),
            )
        })
    }
}
```

## Context

1. **First parameter, always.** `func DoThing(ctx context.Context, ...) error`
2. **Pass it through.** Every function that does I/O takes and passes `ctx`.
3. **Never store in structs.** Context is per-request, not per-object.
4. **Check cancellation in long loops:**
   ```go
   for _, item := range items {
       select {
       case <-ctx.Done():
           return ctx.Err()
       default:
       }
       // process item
   }
   ```

## Concurrency Patterns

### errgroup — parallel work with error collection
```go
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

    return g.Wait() // returns first error, cancels ctx for other goroutines
}
```

### Goroutine leak prevention
```go
// WRONG — goroutine leaks if nobody reads from ch
ch := make(chan Result)
go func() { ch <- doWork() }()

// RIGHT — use context or done channel
func doWorkAsync(ctx context.Context) <-chan Result {
    ch := make(chan Result, 1) // buffered — won't block if receiver gone
    go func() {
        defer close(ch)
        result := doWork()
        select {
        case ch <- result:
        case <-ctx.Done(): // abandon if context cancelled
        }
    }()
    return ch
}
```

## Struct Design

```go
// Use functional options for optional configuration
type Server struct {
    addr    string
    timeout time.Duration
    logger  *slog.Logger
}

type Option func(*Server)

func WithTimeout(d time.Duration) Option { return func(s *Server) { s.timeout = d } }
func WithLogger(l *slog.Logger) Option   { return func(s *Server) { s.logger = l } }

func NewServer(addr string, opts ...Option) *Server {
    s := &Server{addr: addr, timeout: 30 * time.Second, logger: slog.Default()}
    for _, opt := range opts {
        opt(s)
    }
    return s
}
```

## Go Modules

```bash
go mod init github.com/org/myservice    # initialize
go mod tidy                              # add missing, remove unused
go mod vendor                            # vendor dependencies (if required by CI)
```

- Pin major versions: `require github.com/foo/bar/v2 v2.3.1`
- Run `go mod tidy` before committing — don't leave stale entries
- Use `go.sum` for integrity verification — always commit it

## Common Mistakes

1. **Goroutine leak** — every goroutine must have an exit path. Use `context.Context` cancellation.
2. **Naked return in error path** — `return nil, err` without wrapping loses context. Always wrap: `fmt.Errorf("doing X: %w", err)`.
3. **Interface pollution** — defining interfaces with 10+ methods or defining them in the producer package. Keep them small, at the consumer.
4. **Passing pointers to loop variables** — `for _, v := range items { go func() { use(v) }() }` captures the loop variable. Pass as parameter: `go func(v Item) { use(v) }(v)`. (Fixed in Go 1.22+ with loop variable scoping, but be explicit for clarity.)
5. **Using `init()`** — avoid `init()` functions. They make testing hard and create hidden dependencies. Wire in `main()`.
6. **`panic` for error handling** — panic is not an exception. Use it only for programming errors (nil dereference guards), never for expected runtime conditions.
7. **Ignoring `context.Context`** — not passing context through layers means you can't cancel, timeout, or trace requests.
