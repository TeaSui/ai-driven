---
name: messaging-patterns
description: |
  Broker selection guide, cross-broker patterns (idempotency, DLQ, retry backoff, outbox, schema versioning),
  and routing to per-broker reference files for Kafka, RabbitMQ, and AWS SQS.
  Use when: designing event-driven systems, choosing between Kafka/RabbitMQ/SQS, implementing producers or consumers,
  handling message failures, designing event schemas, or reviewing messaging architecture.
  Triggers on: Kafka, RabbitMQ, SQS, message broker, event-driven, consumer, producer, DLQ, dead letter, pub/sub,
  message queue, event streaming, outbox pattern, exactly-once.
---

# Messaging Patterns

## Broker Selection Guide

| Criteria | Kafka | RabbitMQ | AWS SQS |
|----------|-------|----------|---------|
| **Best for** | Event streaming, event sourcing, high-throughput log | Task queues, request-reply, complex routing | Simple queues, serverless, AWS-native |
| **Message retention** | Days/weeks (configurable, replay) | Until consumed (no replay by default) | 14 days max |
| **Ordering** | Per-partition | Per-queue (single consumer) | FIFO queues only |
| **Throughput** | Millions/sec | Tens of thousands/sec | Thousands/sec |
| **Consumer model** | Pull (poll-based) | Push (broker delivers) | Pull (long poll) |
| **Routing** | Topic + partition key | Exchanges + routing keys | SNS topic filters |
| **Replay** | Yes | No (Streams plugin adds it) | No |
| **When NOT to use** | Simple task queues, low volume | Event replay needed, high throughput | Need replay, complex routing |

### Decision Flowchart
1. Need event replay / audit log? → **Kafka**
2. Need complex routing (topic, headers, fanout)? → **RabbitMQ**
3. Simple queue + AWS-native serverless? → **SQS** (see `references/sqs.md`)
4. Event sourcing / CQRS? → **Kafka**
5. Task distribution to workers? → **RabbitMQ**
6. Already using Kafka for other streams? → **Kafka** (avoid running two brokers)

## Per-Broker References

Load the matching reference for implementation details:

| Broker | Reference | Key triggers |
|--------|-----------|--------------|
| Apache Kafka | `references/kafka.md` | `kafka`, `.go` + `segmentio/kafka-go`, `spring-kafka`, `KafkaListener`, `KRaft` |
| RabbitMQ | `references/rabbitmq.md` | `rabbitmq`, `amqp`, `@RabbitListener`, `quorum queue`, `DLX` |
| AWS SQS | `references/sqs.md` | `SQS`, `FIFO`, `visibility timeout`, `redrive`, Lambda + SQS |

---

## Cross-Broker Patterns

These patterns apply regardless of broker. Do not duplicate in reference files — link here.

### Consumer Idempotency

Every consumer must be idempotent. Messages are delivered at-least-once on all three brokers.

Use a transactional inbox with a database uniqueness constraint on `(consumer_name, event_id)`. The following is a storage contract, not an implemented helper:

```text
BEGIN transaction on the SAME database/connection as the business effects
  Atomically insert (consumer_name, event_id) if absent.
  If a committed duplicate exists, skip effects; do not erase its completion.
  Otherwise apply database effects, plus any new outbox records, in this transaction.
COMMIT (return to listener only AFTER commit succeeds)
Acknowledge/delete the broker message or commit its offset.
```

For PostgreSQL, `INSERT ... ON CONFLICT DO NOTHING RETURNING event_id` can distinguish insertion from an existing committed record; the unique constraint serializes concurrent delivery. Do not catch a uniqueness exception and continue inside an aborted transaction. Implement equivalent atomic semantics for the chosen store. The application-owned `inboxHandler.applyOnce` / `applyOnce` names in broker examples mean this entire storage contract; they are not library APIs. For Spring, use a separate proxied transactional service or `TransactionTemplate`; a listener's own `@Transactional` method must not ack before the proxy commits.

**Crash/duplicate rules:**
- Failure before commit rolls back both inbox and effects, so retry can do the work.
- Crash after commit but before ack causes redelivery; the committed inbox makes it harmless.
- Concurrent duplicates wait/conflict at the uniqueness boundary; at most one transaction applies effects.
- Retain inbox records through the full possible replay/redrive window (which can exceed broker retention); do not blindly expire Kafka replay protection after seven days.
- A Redis `SET NX` before processing is a lease/claim, not completion. It cannot atomically commit effects in an unrelated database.
- External effects need a stable vendor idempotency key or a recoverable state machine/outbox worker. Never assume the local inbox transaction makes a payment or HTTP call atomic.
- Include a stable `eventId` in every envelope; keep it unchanged on retries and outbox republishing.

### DLQ / Dead Letter Strategy

```
Main Queue → Consumer → [fail] → Retry (exponential backoff) → [max retries] → DLQ
DLQ → DLQ Processor → Log + Alert + Store → [admin] → Replay after fix
```

**When messages go to DLQ:**
1. Consumer rejects with non-retryable error
2. Max retries exhausted
3. Broker-specific expiry/dead-letter policy: RabbitMQ per-message TTL can route to a configured DLX. SQS retention expiry deletes messages; it is not DLQ redrive.
4. Poison pill (cannot deserialize), when the consumer/container routes it for recovery

These are configured recovery paths, not universal broker guarantees. See [RabbitMQ dead lettering](https://www.rabbitmq.com/docs/dlx) and [SQS redrive/retention](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html).

**DLQ Processor responsibilities:**
1. Log only allowlisted metadata: safe event/correlation identifier, error category, attempt count and partition/offset or delivery tag. Do not log raw bodies, exception messages or headers that can contain PII/credentials.
2. Alert if DLQ depth exceeds threshold (P2)
3. Store failed payloads only in an access-controlled encrypted quarantine with retention limits; application logs contain only a safe reference.
4. Provide replay capability — admin endpoint to reprocess after fix deployed

### Retry Backoff

Use exponential backoff with jitter; never retry immediately on the same error:

| Attempt | Delay |
|---------|-------|
| 1 | 1 s |
| 2 | 2 s |
| 3 | 4 s |
| 4 | 8 s |
| 5+ | DLQ |

### Transactional Outbox Pattern

For critical events where the publish must be atomic with a DB write:

```
1. Write event to outbox table in same DB transaction as business state change
2. Relay reads only committed, unpublished rows (or committed CDC changes) and publishes
3. Mark published only after broker confirmation; retain/retry on failure, then delete after retention
4. Crash after publish/before marking can republish: keep the same eventId and require downstream idempotency; preserve required per-entity order
```

Use when: order placement, payment processing, inventory updates — any case where losing a message is a business failure. Rolled-back work must never publish. See [AWS transactional outbox guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html).

### Event Schema Design

**Envelope (all brokers):**
```json
{
  "eventId":      "uuid-v4",
  "eventType":    "OrderCreated",
  "version":      1,
  "timestamp":    "2026-01-15T10:30:00Z",
  "source":       "order-service",
  "correlationId":"req-abc123",
  "data":         {}
}
```

**Topic / queue naming:**
```
<domain>.<entity>.<event>
orders.order.created
payments.payment.authorized
inventory.stock.reserved
```

**Schema evolution rules:**
1. Add fields — compatible only when consumers tolerate unknown fields and schema compatibility/default rules permit it; verify old/new producer-consumer combinations.
2. Remove fields — deprecate first; remove only after all consumers updated
3. Rename — never. Add new field, deprecate old.
4. Change types — never. Create a versioned event (`OrderCreated.v2`) instead.
5. Use a schema registry (Confluent Schema Registry for Kafka; JSON Schema validation for RabbitMQ/SQS) to enforce compatibility.

## Common Mistakes

1. **Kafka for simple task queues** — RabbitMQ is simpler and better for work distribution. Kafka shines for streaming and replay.
2. **Not handling poison pills** — a message that always fails blocks the partition or queue. DLQ after max retries.
3. **Acknowledging before the database transaction commits** — a crash loses effects. Commit inbox + effects first, then ack; redelivery after commit must be harmless.
4. **Single partition for ordering** — destroys throughput. Partition by entity ID for per-entity ordering with parallelism.
5. **No schema evolution strategy** — changing schemas without versioning breaks consumers.
6. **Fire-and-forget publishing** — if publish fails and you don't handle it, the event is lost. Use the outbox pattern for critical events.
