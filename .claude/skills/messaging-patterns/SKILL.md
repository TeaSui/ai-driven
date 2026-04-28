---
name: messaging-patterns
description: |
  Kafka and RabbitMQ patterns: broker selection guide, producer/consumer implementation, dead letter queues,
  consumer idempotency, retry strategies, exactly-once semantics, event schema design, and partition strategies.
  Use when: designing event-driven systems, choosing between Kafka and RabbitMQ, implementing producers or consumers,
  handling message failures, or designing event schemas.
  Triggers on: Kafka, RabbitMQ, message broker, event-driven, consumer, producer, DLQ, dead letter, pub/sub, message queue.
---

# Messaging Patterns

## Broker Selection Guide

| Criteria | Kafka | RabbitMQ | AWS SQS |
|----------|-------|----------|---------|
| **Best for** | Event streaming, event sourcing, high-throughput log | Task queues, request-reply, complex routing | Simple queues, serverless, AWS-native |
| **Message retention** | Days/weeks (replay) | Until consumed (no replay) | 14 days max |
| **Ordering** | Per-partition | Per-queue (single consumer) | FIFO queues only |
| **Throughput** | Millions/sec | Tens of thousands/sec | Thousands/sec |
| **Consumer model** | Pull (poll-based) | Push (broker delivers) | Pull |
| **Routing** | Topic partitions | Exchanges + routing keys | SNS topic filters |
| **When NOT to use** | Simple task queues, low volume | Event replay needed, high throughput | Need replay, complex routing |

### Decision Flowchart
1. Need event replay / audit log? -> **Kafka**
2. Need complex routing (topic, headers, fanout)? -> **RabbitMQ**
3. Simple queue + AWS serverless? -> **SQS** (see aws-infrastructure-subagent)
4. Event sourcing / CQRS? -> **Kafka**
5. Task distribution to workers? -> **RabbitMQ**
6. Already using Kafka for other streams? -> **Kafka** (avoid two brokers when one suffices)

## Kafka Patterns

### Producer (Java/Spring)

```java
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties props) {
        Map<String, Object> config = props.buildProducerProperties(null);
        config.put(ProducerConfig.ACKS_CONFIG, "all");              // wait for all replicas
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // exactly-once producer
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }
}

@Service
public class OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafka;

    public void publishOrderCreated(Order order) {
        var event = new OrderCreatedEvent(
            UUID.randomUUID().toString(),    // eventId — for consumer idempotency
            Instant.now(),                    // timestamp
            order.getId(),                    // aggregate ID
            order.toEventPayload()
        );

        // Use order ID as key — all events for same order go to same partition (ordering)
        kafka.send("orders.created", order.getId(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish OrderCreated for {}", order.getId(), ex);
                    // Store in outbox for retry — do NOT swallow
                }
            });
    }
}
```

### Consumer (Java/Spring)

```java
@Component
public class OrderEventConsumer {

    private final IdempotencyStore idempotencyStore;
    private final OrderProjection orderProjection;

    @KafkaListener(
        topics = "orders.created",
        groupId = "inventory-service",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderCreated(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        // Idempotency check — skip if already processed
        if (idempotencyStore.isDuplicate(event.getEventId())) {
            log.info("Skipping duplicate event {}", event.getEventId());
            ack.acknowledge();
            return;
        }

        try {
            orderProjection.applyOrderCreated(event);
            idempotencyStore.markProcessed(event.getEventId());
            ack.acknowledge(); // manual commit after successful processing
        } catch (RetryableException e) {
            // Don't ack — Kafka will redeliver
            throw e;
        } catch (Exception e) {
            log.error("Non-retryable error processing event {}", event.getEventId(), e);
            // Send to DLT (dead letter topic) and ack to move forward
            publishToDlt(event, e);
            ack.acknowledge();
        }
    }
}
```

### Consumer Config
```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
        ConsumerFactory<String, Object> cf) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
    factory.setConsumerFactory(cf);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
    factory.setConcurrency(3); // 3 threads = 3 partitions consumed in parallel
    factory.setCommonErrorHandler(new DefaultErrorHandler(
        new DeadLetterPublishingRecoverer(kafkaTemplate),
        new FixedBackOff(1000L, 3) // retry 3 times, 1 second apart
    ));
    return factory;
}
```

### Partition Strategy
- **Key = entity ID** (e.g., orderId) — all events for same entity in order
- **Key = null** — round-robin distribution for max throughput
- **Partition count** — start with `max(expected_throughput / 10MB_sec, consumer_count)`. Can increase later, never decrease.

### Producer (Go)

```go
// Using segmentio/kafka-go
func (p *Producer) PublishOrderCreated(ctx context.Context, order *Order) error {
    event := OrderCreatedEvent{
        EventID:   uuid.New().String(),
        Timestamp: time.Now().UTC(),
        OrderID:   order.ID,
        Payload:   order.ToEventPayload(),
    }

    value, err := json.Marshal(event)
    if err != nil {
        return fmt.Errorf("marshal event: %w", err)
    }

    err = p.writer.WriteMessages(ctx, kafka.Message{
        Key:   []byte(order.ID), // partition by order ID
        Value: value,
        Headers: []kafka.Header{
            {Key: "event-type", Value: []byte("OrderCreated")},
            {Key: "event-id", Value: []byte(event.EventID)},
        },
    })
    if err != nil {
        return fmt.Errorf("publish OrderCreated: %w", err)
    }
    return nil
}
```

## RabbitMQ Patterns

### Exchange Types
| Type | Routing | Use case |
|------|---------|----------|
| **Direct** | Exact routing key match | Task queues, point-to-point |
| **Topic** | Wildcard routing key (`order.*`, `#.error`) | Event filtering by category |
| **Fanout** | All bound queues | Broadcast to all consumers |
| **Headers** | Header attribute matching | Complex routing rules |

### Producer (Java/Spring)

```java
@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange("order.events", true, false); // durable, not auto-delete
    }

    @Bean
    public Queue inventoryQueue() {
        return QueueBuilder.durable("inventory.order-created")
            .withArgument("x-dead-letter-exchange", "dlx.order.events")
            .withArgument("x-dead-letter-routing-key", "order.created.dlq")
            .build();
    }

    @Bean
    public Binding inventoryBinding(Queue inventoryQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(inventoryQueue).to(orderExchange).with("order.created");
    }
}

@Service
public class OrderEventPublisher {

    private final RabbitTemplate rabbit;

    public void publishOrderCreated(Order order) {
        var event = new OrderCreatedEvent(UUID.randomUUID().toString(), order);
        rabbit.convertAndSend("order.events", "order.created", event, message -> {
            message.getMessageProperties().setMessageId(event.getEventId());
            message.getMessageProperties().setContentType("application/json");
            return message;
        });
    }
}
```

### Consumer (Java/Spring)

```java
@Component
public class InventoryConsumer {

    @RabbitListener(queues = "inventory.order-created", concurrency = "3-10")
    public void onOrderCreated(OrderCreatedEvent event, Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        try {
            if (idempotencyStore.isDuplicate(event.getEventId())) {
                channel.basicAck(tag, false);
                return;
            }
            inventoryService.reserveStock(event);
            idempotencyStore.markProcessed(event.getEventId());
            channel.basicAck(tag, false);
        } catch (RetryableException e) {
            channel.basicNack(tag, false, true);  // requeue for retry
        } catch (Exception e) {
            channel.basicNack(tag, false, false); // reject — goes to DLQ
        }
    }
}
```

## Dead Letter Queue (DLQ) Strategy

### When messages go to DLQ
1. Consumer rejects (non-retryable error)
2. Max retries exhausted
3. Message TTL expired
4. Message cannot be deserialized (poison pill)

### DLQ Processing Pattern
```
Main Queue → Consumer → [fail] → Retry Queue (with TTL) → Main Queue
                                  └→ [max retries] → DLQ → DLQ Processor → Alert + Store
```

### DLQ Processor responsibilities
1. **Log** the failure with full context (message, error, attempt count)
2. **Alert** if DLQ depth exceeds threshold (P2 alert)
3. **Store** failed messages for investigation (S3 or database)
4. **Replay** capability — admin endpoint to reprocess DLQ messages after fix deployed

## Consumer Idempotency

**Every consumer must be idempotent.** Messages will be delivered at-least-once.

```java
// Idempotency store — Redis or database
public class RedisIdempotencyStore {
    private final StringRedisTemplate redis;

    public boolean isDuplicate(String eventId) {
        return Boolean.FALSE.equals(
            redis.opsForValue().setIfAbsent("idem:" + eventId, "1", Duration.ofDays(7))
        );
    }

    public void markProcessed(String eventId) {
        redis.opsForValue().set("idem:" + eventId, "1", Duration.ofDays(7));
    }
}
```

**TTL for idempotency keys:** Match the message retention period. Kafka default 7 days = 7 day TTL.

## Event Schema Design

### Envelope
```json
{
  "eventId": "uuid",
  "eventType": "OrderCreated",
  "timestamp": "2026-01-15T10:30:00Z",
  "version": 1,
  "source": "order-service",
  "correlationId": "req-abc123",
  "data": { }
}
```

### Schema Evolution Rules
1. **Add fields** — always safe (new fields with defaults)
2. **Remove fields** — deprecate first, remove after all consumers updated
3. **Rename fields** — never. Add new field, deprecate old.
4. **Change types** — never. Version the event instead: `OrderCreated.v2`
5. **Use a schema registry** (Confluent for Kafka, or JSON Schema validation) to enforce compatibility

### Topic Naming Convention
```
<domain>.<entity>.<event>
orders.order.created
payments.payment.authorized
inventory.stock.reserved
```

## Common Mistakes

1. **Kafka for simple task queues** — RabbitMQ is simpler and better for task distribution. Kafka shines for event streaming and replay.
2. **Not handling poison pills** — a message that always fails will block the partition/queue forever. DLQ after max retries.
3. **Processing before committing offset** — if consumer crashes after processing but before commit, message is reprocessed. Idempotency is mandatory.
4. **Single partition for ordering** — destroys throughput. Partition by entity ID for per-entity ordering with parallelism.
5. **No schema evolution strategy** — changing event schemas without versioning breaks consumers. Design for evolution from day one.
6. **Fire-and-forget publishing** — if publish fails and you don't handle it, the event is lost. Use transactional outbox pattern for critical events.
