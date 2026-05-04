---
name: database-patterns
description: |
  Database query and design patterns: CTE-first query approach for MySQL/PostgreSQL, indexing strategies,
  zero-downtime migrations, DynamoDB single-table access pattern design, and Redis caching/data structures.
  Use when: writing SQL queries, designing database indexes, planning migrations, modeling DynamoDB access patterns,
  implementing Redis caching, or optimizing query performance.
  Triggers on: SQL, CTE, common table expression, query, index, migration, DynamoDB access pattern,
  single-table design, Redis, cache, PostgreSQL, MySQL, database performance.
---

# Database Patterns

## CTE-First Query Approach

**Default to CTEs (Common Table Expressions) over subqueries and temp tables.** CTEs are more readable, composable, and debuggable. Each CTE is a named, testable step.

### Pattern: Replace Subqueries with CTEs

```sql
-- AVOID: nested subqueries
SELECT o.id, o.total, c.name
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE o.id IN (
    SELECT order_id FROM order_items
    WHERE product_id IN (
        SELECT id FROM products WHERE category = 'electronics'
    )
    GROUP BY order_id
    HAVING SUM(quantity) > 5
);

-- PREFER: CTEs — each step is named and testable independently
WITH electronics_products AS (
    SELECT id
    FROM products
    WHERE category = 'electronics'
),
bulk_orders AS (
    SELECT order_id
    FROM order_items
    WHERE product_id IN (SELECT id FROM electronics_products)
    GROUP BY order_id
    HAVING SUM(quantity) > 5
)
SELECT o.id, o.total, c.name
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE o.id IN (SELECT order_id FROM bulk_orders);
```

### Pattern: Multi-Step Business Logic

```sql
-- Monthly revenue report with running totals
WITH daily_revenue AS (
    SELECT
        DATE(created_at) AS revenue_date,
        SUM(total) AS daily_total,
        COUNT(*) AS order_count
    FROM orders
    WHERE created_at >= DATE_TRUNC('month', CURRENT_DATE)
      AND status = 'completed'
    GROUP BY DATE(created_at)
),
with_running_total AS (
    SELECT
        revenue_date,
        daily_total,
        order_count,
        SUM(daily_total) OVER (ORDER BY revenue_date) AS running_total,
        AVG(daily_total) OVER (ORDER BY revenue_date ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS moving_avg_7d
    FROM daily_revenue
)
SELECT *
FROM with_running_total
ORDER BY revenue_date;
```

### Pattern: Recursive CTE (Hierarchies)

```sql
-- Organization hierarchy (PostgreSQL and MySQL 8+)
WITH RECURSIVE org_tree AS (
    -- Base: top-level managers
    SELECT id, name, manager_id, 1 AS depth, ARRAY[id] AS path
    FROM employees
    WHERE manager_id IS NULL

    UNION ALL

    -- Recursive: each employee's reports
    SELECT e.id, e.name, e.manager_id, ot.depth + 1, ot.path || e.id
    FROM employees e
    JOIN org_tree ot ON e.manager_id = ot.id
    WHERE ot.depth < 10 -- safety limit to prevent infinite recursion
)
SELECT id, name, depth, path
FROM org_tree
ORDER BY path;
```

### Pattern: CTE for Upsert with Returning

```sql
-- PostgreSQL: insert or update, return the result
WITH upserted AS (
    INSERT INTO user_preferences (user_id, preference_key, preference_value)
    VALUES ($1, $2, $3)
    ON CONFLICT (user_id, preference_key)
    DO UPDATE SET
        preference_value = EXCLUDED.preference_value,
        updated_at = NOW()
    RETURNING *
)
SELECT * FROM upserted;
```

### When NOT to Use CTEs
- **Simple single-table queries** — `SELECT * FROM users WHERE id = $1` does not need a CTE.
- **Performance-critical hot paths** where the query planner does better with subqueries (profile first, optimize second).
- **MySQL < 8.0** — CTEs not supported (but MySQL 8+ supports them fully).

## Indexing Strategies

### PostgreSQL

```sql
-- Composite index: leftmost prefix rule applies
CREATE INDEX idx_orders_customer_status ON orders (customer_id, status);
-- Supports: WHERE customer_id = X
-- Supports: WHERE customer_id = X AND status = Y
-- Does NOT support: WHERE status = Y (alone)

-- Partial index: index only the rows you query
CREATE INDEX idx_orders_pending ON orders (created_at)
    WHERE status = 'pending';
-- Much smaller than full index, faster for the specific query pattern

-- Expression index
CREATE INDEX idx_users_email_lower ON users (LOWER(email));
-- Supports: WHERE LOWER(email) = 'user@test.com'

-- CONCURRENTLY: no table lock during creation (PostgreSQL)
CREATE INDEX CONCURRENTLY idx_orders_created ON orders (created_at);
```

### MySQL

```sql
-- Covering index: all queried columns in the index (avoids table lookup)
CREATE INDEX idx_orders_cover ON orders (customer_id, status, total, created_at);
-- SELECT status, total FROM orders WHERE customer_id = X → index-only scan

-- Prefix index for long strings
CREATE INDEX idx_users_email ON users (email(50));

-- Force index hint (last resort — prefer fixing the query)
SELECT * FROM orders FORCE INDEX (idx_orders_customer_status) WHERE ...;
```

### Index Decision Checklist
1. **Is this column in WHERE, JOIN, or ORDER BY?** If yes, consider indexing.
2. **What's the cardinality?** Boolean columns rarely benefit from B-tree indexes.
3. **Read-heavy or write-heavy?** Every index slows writes. Over-indexing kills insert performance.
4. **Can I use a partial index?** If you only query a subset (e.g., active records), partial index is better.
5. **Check `EXPLAIN ANALYZE`** before and after — verify the index is actually used.

## Zero-Downtime Migration Patterns

### Expand-then-Contract (for column changes)

**Phase 1 — Expand:** Add new column (nullable), deploy code that writes to BOTH columns.
```sql
ALTER TABLE orders ADD COLUMN status_v2 VARCHAR(50); -- nullable, no default needed yet
```

**Phase 2 — Backfill:** Populate new column from old.
```sql
-- Batch update to avoid long locks
UPDATE orders SET status_v2 = status WHERE status_v2 IS NULL AND id BETWEEN $1 AND $2;
```

**Phase 3 — Switch:** Deploy code that reads from new column. Verify.

**Phase 4 — Contract:** Drop old column (after all code stops using it).
```sql
ALTER TABLE orders DROP COLUMN status;
ALTER TABLE orders RENAME COLUMN status_v2 TO status;
```

### Migration Safety Rules
1. **Never DROP COLUMN in the same deploy as the code change.** Old code instances may still need it.
2. **Add columns as NULLABLE** or with a server-side DEFAULT. `NOT NULL` without default locks the table for backfill.
3. **CREATE INDEX CONCURRENTLY** (PostgreSQL) to avoid blocking writes.
4. **Large table ALTERs** — use `pt-online-schema-change` (MySQL) or `pg_repack` (PostgreSQL) for tables > 1M rows.
5. **Test migrations against a production-size dataset.** A migration that takes 1 second on dev can lock production for minutes.

### Flyway Naming Convention
```
V1__create_orders_table.sql
V2__add_orders_status_index.sql
V3__add_customer_email_column.sql
R__refresh_order_summary_view.sql  # repeatable migration
```

## DynamoDB Access Pattern Design

### Single-Table Design Process
1. **List access patterns first** — what queries does the application need?
2. **Design keys to support those queries** — PK/SK for main, GSIs for alternative patterns.
3. **Document in an access pattern table.**

### Example: E-commerce

| Access Pattern | PK | SK | GSI |
|---|---|---|---|
| Get order by ID | `ORDER#<orderId>` | `METADATA` | |
| Get customer's orders | `CUSTOMER#<custId>` | `ORDER#<timestamp>#<orderId>` | |
| Get order items | `ORDER#<orderId>` | `ITEM#<itemId>` | |
| Orders by status | | | GSI1PK: `STATUS#<status>`, GSI1SK: `ORDER#<timestamp>` |
| Recent orders (global) | | | GSI1PK: `ALL_ORDERS`, GSI1SK: `<timestamp>#<orderId>` |

### Query Patterns (Application Code)

```java
// Get customer's orders (sorted by date, newest first)
QueryRequest query = QueryRequest.builder()
    .tableName(TABLE_NAME)
    .keyConditionExpression("PK = :pk AND begins_with(SK, :prefix)")
    .expressionAttributeValues(Map.of(
        ":pk", AttributeValue.fromS("CUSTOMER#" + customerId),
        ":prefix", AttributeValue.fromS("ORDER#")
    ))
    .scanIndexForward(false) // newest first
    .limit(20)
    .build();
```

```go
// Go — using aws-sdk-go-v2
input := &dynamodb.QueryInput{
    TableName:              aws.String(tableName),
    KeyConditionExpression: aws.String("PK = :pk AND begins_with(SK, :prefix)"),
    ExpressionAttributeValues: map[string]types.AttributeValue{
        ":pk":     &types.AttributeValueMemberS{Value: "CUSTOMER#" + customerID},
        ":prefix": &types.AttributeValueMemberS{Value: "ORDER#"},
    },
    ScanIndexForward: aws.Bool(false),
    Limit:           aws.Int32(20),
}
```

### DynamoDB Anti-Patterns
1. **Scan operations** — scans read the entire table. If you need a scan, you're missing an index.
2. **Hot partitions** — a single PK getting disproportionate traffic. Distribute with write sharding: `STATUS#active#<shard>`.
3. **Large items** — DynamoDB max 400KB per item. Store large blobs in S3, reference by key.
4. **Relying on consistent reads everywhere** — eventually consistent reads are 2x cheaper and sufficient for most patterns.

## Redis Patterns

### Data Structure Selection

| Need | Redis Type | Example |
|------|-----------|---------|
| Cache a value | STRING | `SET user:123 "{json}" EX 3600` |
| Cache with fields | HASH | `HSET user:123 name "John" email "j@t.com"` |
| Leaderboard / ranking | SORTED SET | `ZADD leaderboard 1500 "user:123"` |
| Rate limiting | STRING + INCR | `INCR rate:user:123:min` + `EXPIRE` |
| Session store | HASH | `HSET session:abc user_id 123 role admin` |
| Distributed lock | STRING + NX | `SET lock:order:456 owner NX EX 30` |
| Recent items / feed | LIST | `LPUSH recent:user:123 item_id` + `LTRIM` |
| Unique tracking | SET or HyperLogLog | `SADD visitors:2026-04-13 "user:123"` |

### Cache-Aside Pattern (Most Common)

```java
public Order getOrder(String orderId) {
    // 1. Check cache
    String cached = redis.opsForValue().get("order:" + orderId);
    if (cached != null) {
        return objectMapper.readValue(cached, Order.class);
    }

    // 2. Cache miss — load from DB
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

    // 3. Populate cache with TTL
    redis.opsForValue().set(
        "order:" + orderId,
        objectMapper.writeValueAsString(order),
        Duration.ofMinutes(30)
    );

    return order;
}

// Invalidate on write
@Transactional
public Order updateOrder(String orderId, UpdateOrderRequest req) {
    Order order = orderRepository.findById(orderId).orElseThrow(...);
    order.apply(req);
    order = orderRepository.save(order);
    redis.delete("order:" + orderId); // invalidate cache
    return order;
}
```

### Cache Key Naming Convention
```
<entity>:<id>                    → order:456
<entity>:<id>:<field>            → user:123:preferences
<entity>:list:<filter>:<page>    → product:list:electronics:1
rate:<scope>:<window>            → rate:user:123:min
lock:<resource>                  → lock:order:456
```

### Cache Invalidation Rules
1. **Write-through invalidation** — delete cache key on every write to the source. Simple and safe.
2. **TTL as safety net** — always set TTL even with active invalidation. Prevents stale data if invalidation fails.
3. **Cache stampede protection** — use distributed locks or probabilistic early expiration for hot keys.
4. **Never cache errors** — a temporary DB outage should not fill your cache with error responses.

## Common Mistakes

1. **N+1 queries** — fetching a list then querying each item individually. Use JOINs or batch fetches.
2. **Missing indexes on foreign keys** — every FK column should be indexed for JOIN performance.
3. **SELECT * in production** — select only needed columns. Especially with wide tables or JSONB.
4. **Caching without TTL** — data becomes stale with no expiration. Always set a TTL.
5. **Over-indexing** — each index slows inserts/updates. Index for actual query patterns, not "just in case."
6. **DynamoDB scan instead of query** — always query with PK. If you need scan, add a GSI.
