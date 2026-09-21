---
name: messaging-patterns-kafka
description: >
  Apache Kafka implementation reference: producer idempotence + transactions, consumer groups,
  partition strategy, exactly-once semantics, schema registry, KRaft mode, Spring Kafka, kafka-go.
type: reference
---

# Kafka Reference

## When to use

Kafka over RabbitMQ/SQS when: event replay needed, event sourcing/CQRS, high-throughput streaming (>100k msg/s), audit log, fan-out to many consumers independently, stream processing (Kafka Streams / Flink).

Cross-broker selection guide: see parent `SKILL.md`.

## Core concepts

| Concept | Description |
|---------|-------------|
| **Topic** | Named stream; append-only log |
| **Partition** | Ordered, immutable sub-log; unit of parallelism |
| **Consumer group** | Set of consumers sharing partition assignment; each partition → one consumer at a time |
| **Offset** | Position within a partition; committed by consumer |
| **Replication factor** | Copies of each partition across brokers (≥3 for production) |
| **KRaft mode** | ZooKeeper-free metadata management (default since Kafka 3.3, ZK removed in 4.0) |

## Producer patterns

### Idempotent producer (at-least-once → exactly-once per partition)

```java
// Spring Boot — application.yml
spring:
  kafka:
    producer:
      acks: all                    # wait for all in-sync replicas
      retries: 2147483647          # retry until delivery.timeout.ms
      enable-idempotence: true     # deduplicates retries at broker
      properties:
        max.in.flight.requests.per.connection: 5   # must be ≤5 with idempotence
        delivery.timeout.ms: 120000
```

```java
@Service
public class OrderEventPublisher {
    private final KafkaTemplate<String, Object> kafka;

    public void publishOrderCreated(Order order) {
        var event = new OrderCreatedEvent(UUID.randomUUID().toString(), Instant.now(), order);
        // Key = entity ID → all events for same order land on same partition (ordering)
        kafka.send("orders.order.created", order.getId(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Publish failed for order {}", order.getId(), ex);
                    // Persist to outbox for retry — do NOT swallow
                }
            });
    }
}
```

### Transactional producer (exactly-once across partitions)

```java
config.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "order-producer-1");
// then:
producer.initTransactions();
producer.beginTransaction();
try {
    producer.send(record1);
    producer.send(record2);
    // Commit consumer offsets atomically with the produce
    producer.sendOffsetsToTransaction(offsets, groupMetadata);
    producer.commitTransaction();
} catch (Exception e) {
    producer.abortTransaction();
}
```

Use transactions when you need atomic read-process-write (consume from topic A, produce to topic B atomically).

### Go producer (segmentio/kafka-go)

```go
writer := &kafka.Writer{
    Addr:         kafka.TCP("broker:9092"),
    Topic:        "orders.order.created",
    Balancer:     &kafka.Hash{},    // partition by key hash
    RequiredAcks: kafka.RequireAll, // acks=all
    Async:        false,            // synchronous — wait for ack
}
err := writer.WriteMessages(ctx, kafka.Message{
    Key:   []byte(order.ID),
    Value: value,
    Headers: []kafka.Header{
        {Key: "event-type", Value: []byte("OrderCreated")},
        {Key: "event-id",   Value: []byte(eventID)},
    },
})
```

## Consumer patterns

### Spring Kafka consumer (manual ack)

```java
@KafkaListener(
    topics = "orders.order.created",
    groupId = "inventory-service",
    containerFactory = "kafkaListenerContainerFactory"
)
public void onOrderCreated(
        @Payload OrderCreatedEvent event,
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) long offset,
        Acknowledgment ack) {

    if (idempotencyStore.isDuplicate(event.getEventId())) {
        ack.acknowledge();
        return;
    }
    try {
        orderProjection.applyOrderCreated(event);
        idempotencyStore.markProcessed(event.getEventId());
        ack.acknowledge();
    } catch (RetryableException e) {
        throw e; // do not ack — broker redelivers
    } catch (Exception e) {
        log.error("Non-retryable error {}", event.getEventId(), e);
        publishToDlt(event, e);
        ack.acknowledge(); // advance past poison pill
    }
}
```

### Consumer container configuration

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
        ConsumerFactory<String, Object> cf, KafkaTemplate<String, Object> template) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
    factory.setConsumerFactory(cf);
    factory.getContainerProperties().setAckMode(AckMode.MANUAL);
    factory.setConcurrency(3);  // one thread per partition (≤ partition count)
    factory.setCommonErrorHandler(new DefaultErrorHandler(
        new DeadLetterPublishingRecoverer(template),
        new ExponentialBackOffWithMaxRetries(4)  // 4 retries, then DLT
    ));
    return factory;
}
```

### Rebalance listener (save offsets on revoke)

Implement `ConsumerRebalanceListener.onPartitionsRevoked` to commit offsets before rebalance completes. With cooperative rebalancing (`CooperativeStickyAssignor`), only revoked partitions pause — preferred for large consumer groups.

```yaml
spring.kafka.consumer.properties:
  partition.assignment.strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor
```

## Delivery guarantees

| Mode | Config | Notes |
|------|--------|-------|
| At-most-once | `acks=0` or auto-commit before processing | Messages can be lost |
| At-least-once | `acks=all` + manual commit after processing | Duplicates possible; require idempotent consumers |
| Exactly-once | Idempotent producer + transactional API + `isolation.level=read_committed` | Highest overhead; use for financial/inventory critical paths |

## Failure handling (DLQ/retry)

Kafka uses a **Dead Letter Topic (DLT)** — a separate topic, not a queue construct.

Pattern with Spring `DefaultErrorHandler`:
1. Retry N times with backoff (in-memory, same thread)
2. On exhaustion → `DeadLetterPublishingRecoverer` publishes to `<topic>.DLT`
3. DLT consumer logs, alerts, stores for replay

Name DLTs consistently: `orders.order.created.DLT`

Cross-broker DLQ strategy: see parent `SKILL.md`.

## Schema & evolution

**Schema Registry** (Confluent or AWS Glue):
- Enforce Avro/Protobuf/JSON Schema compatibility before publish
- Compatibility modes: `BACKWARD` (default), `FORWARD`, `FULL`
- Producers register schema; consumers resolve by schema ID embedded in message

**Without a registry** (JSON only):
- Include `version` field in envelope
- Consumer tolerates unknown fields (Jackson: `FAIL_ON_UNKNOWN_PROPERTIES = false`)

Schema evolution rules: see parent `SKILL.md`.

## Operational concerns

### Partition sizing
- Start with `max(expected_throughput_MB_per_sec / 10, target_consumer_count)`
- Can increase partitions later — cannot decrease without re-creating topic
- Adding partitions invalidates key-based ordering for existing data

### Retention
- `log.retention.hours` (default 168 = 7 days) — set per-topic for replay window
- `log.cleanup.policy=compact` for event-sourced state topics (keeps latest value per key)
- `log.cleanup.policy=delete` (default) for event logs

### KRaft (Kafka 3.3+, ZK removed in 4.0)
- No ZooKeeper dependency in new clusters
- Controller quorum handles metadata; `kafka-storage.sh format` replaces ZK init
- Migration from ZK clusters: use `kafka-metadata-migration` tool

### Monitoring
- `consumer_lag` per group+partition (alert on sustained lag growth)
- `under_replicated_partitions` (alert on > 0)
- `request_handler_pool_idle_percent` (alert on < 30%)

## Common pitfalls

1. **Too few partitions** — limits throughput and consumer parallelism. Size partitions at topic creation.
2. **`acks=1` with idempotence** — idempotence requires `acks=all`. Spring Boot auto-sets this; verify when configuring manually.
3. **Auto-commit with manual processing** — auto-commit commits before processing completes. Set `enable.auto.commit=false` and use `AckMode.MANUAL`.
4. **Blocking in poll loop** — processing time > `max.poll.interval.ms` triggers consumer group rebalance. Keep handlers fast or increase `max.poll.interval.ms`.
5. **Ignoring `onPartitionsRevoked`** — uncommitted offsets on revoke cause reprocessing. Always commit before returning from `onPartitionsRevoked`.
6. **`isolation.level` mismatch** — if using transactional producers, consumers must set `isolation.level=read_committed` to avoid reading uncommitted data.
