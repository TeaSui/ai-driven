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

```java
// Redis-backed idempotency store (TTL = message retention period)
public class RedisIdempotencyStore {
    private final StringRedisTemplate redis;

    public boolean isDuplicate(String eventId) {
        // setIfAbsent returns false if key already exists → duplicate
        return Boolean.FALSE.equals(
            redis.opsForValue().setIfAbsent("idem:" + eventId, "1", Duration.ofDays(7))
        );
    }
}
```

**Rules:**
- TTL on idempotency keys must be ≥ message retention period of the broker.
- Include `eventId` in every message envelope (UUID generated at publish time).
- Check idempotency **before** processing; mark processed **after** successful processing + ack.

### DLQ / Dead Letter Strategy

```
Main Queue → Consumer → [fail] → Retry (exponential backoff) → [max retries] → DLQ
DLQ → DLQ Processor → Log + Alert + Store → [admin] → Replay after fix
```

**When messages go to DLQ:**
1. Consumer rejects with non-retryable error
2. Max retries exhausted
3. Message TTL expired
4. Poison pill (cannot deserialize)

**DLQ Processor responsibilities:**
1. Log failure with full context (message body, error, attempt count, partition/offset or delivery tag)
2. Alert if DLQ depth exceeds threshold (P2)
3. Store failed messages for investigation (S3 or DB)
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
2. Outbox relay (Debezium CDC or polling worker) reads uncommitted rows and publishes
3. Mark row as published; delete after retention window
```

Use when: order placement, payment processing, inventory updates — any case where losing a message is a business failure.

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
1. Add fields — always safe (new fields with defaults)
2. Remove fields — deprecate first; remove only after all consumers updated
3. Rename — never. Add new field, deprecate old.
4. Change types — never. Create a versioned event (`OrderCreated.v2`) instead.
5. Use a schema registry (Confluent Schema Registry for Kafka; JSON Schema validation for RabbitMQ/SQS) to enforce compatibility.

## Common Mistakes

1. **Kafka for simple task queues** — RabbitMQ is simpler and better for work distribution. Kafka shines for streaming and replay.
2. **Not handling poison pills** — a message that always fails blocks the partition or queue. DLQ after max retries.
3. **Processing before committing offset / ack** — crash after processing but before ack = reprocessing. Idempotency is mandatory.
4. **Single partition for ordering** — destroys throughput. Partition by entity ID for per-entity ordering with parallelism.
5. **No schema evolution strategy** — changing schemas without versioning breaks consumers.
6. **Fire-and-forget publishing** — if publish fails and you don't handle it, the event is lost. Use the outbox pattern for critical events.
