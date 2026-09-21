---
name: messaging-patterns-rabbitmq
description: >
  RabbitMQ implementation reference: exchange types, quorum vs classic queues, publisher confirms,
  consumer acknowledgments, DLX patterns, Streams plugin, Spring AMQP, clustering basics.
type: reference
---

# RabbitMQ Reference

## When to use

RabbitMQ over Kafka/SQS when: complex routing (topic/fanout/headers exchanges), task distribution to workers, request-reply patterns, lower message volume, existing AMQP infrastructure, need per-message TTL or priority queues.

Cross-broker selection guide: see parent `SKILL.md`.

## Core concepts

| Concept | Description |
|---------|-------------|
| **Exchange** | Receives messages from producers; routes to queues via bindings |
| **Queue** | Stores messages until consumed; durable or transient |
| **Binding** | Rule linking exchange to queue (with optional routing key) |
| **Routing key** | String used by direct/topic exchanges to match bindings |
| **Virtual host** | Logical isolation; separate namespace for exchanges/queues |
| **Channel** | Multiplexed connection within a single TCP connection |

## Producer patterns

### Exchange types

| Type | Routing rule | Use case |
|------|-------------|----------|
| **Direct** | Exact routing key match | Task queues, point-to-point |
| **Topic** | Wildcard match (`order.*`, `#.error`) | Event filtering by category |
| **Fanout** | All bound queues | Broadcast to all consumers |
| **Headers** | Message header attributes | Complex routing without routing keys |

### Spring AMQP configuration

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
            .quorum()                                              // quorum queue (preferred)
            .withArgument("x-dead-letter-exchange", "dlx.events")
            .withArgument("x-dead-letter-routing-key", "order.created.dlq")
            .withArgument("x-message-ttl", 86400000)               // 24h TTL
            .build();
    }

    @Bean
    public Binding inventoryBinding(Queue inventoryQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(inventoryQueue).to(orderExchange).with("order.created");
    }
}
```

### Publisher confirms (mandatory for production)

```java
@Bean
public RabbitTemplate rabbitTemplate(ConnectionFactory cf) {
    var template = new RabbitTemplate(cf);
    template.setConfirmCallback((correlationData, ack, cause) -> {
        if (!ack) {
            log.error("Publish NACK: {} — {}", correlationData, cause);
            // trigger outbox retry
        }
    });
    template.setReturnsCallback(returned -> {
        log.error("Message returned unroutable: {}", returned.getMessage());
        // no matching binding — alert or route to fallback
    });
    return template;
}

// ConnectionFactory must enable confirms:
// spring.rabbitmq.publisher-confirm-type: correlated
// spring.rabbitmq.publisher-returns: true
```

**Publisher confirms are mandatory for quorum queues.** Confirm is sent only after the message is written and fsynced by a quorum of nodes.

## Consumer patterns

### Spring AMQP listener (manual ack)

```java
@Component
public class InventoryConsumer {

    @RabbitListener(queues = "inventory.order-created", concurrency = "3-10")
    public void onOrderCreated(
            OrderCreatedEvent event,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {

        if (idempotencyStore.isDuplicate(event.getEventId())) {
            channel.basicAck(tag, false);
            return;
        }
        try {
            inventoryService.reserveStock(event);
            idempotencyStore.markProcessed(event.getEventId());
            channel.basicAck(tag, false);                    // success — delete from queue
        } catch (RetryableException e) {
            channel.basicNack(tag, false, true);             // requeue for retry
        } catch (Exception e) {
            log.error("Non-retryable {}", event.getEventId(), e);
            channel.basicNack(tag, false, false);            // reject → goes to DLX
        }
    }
}
```

**Ack semantics:**
- `basicAck` — message processed successfully; broker deletes it
- `basicNack(requeue=true)` — requeue (use for transient failures only; avoid hot requeue loop)
- `basicNack(requeue=false)` — reject; routes to DLX if configured, otherwise discarded

### Prefetch (QoS)

```java
// application.yml
spring:
  rabbitmq:
    listener:
      simple:
        prefetch: 10          # max unacked messages per consumer channel
        acknowledge-mode: manual
```

Set prefetch to limit in-flight messages per consumer. Low prefetch = fairer distribution; high prefetch = better throughput on fast consumers.

## Delivery guarantees

| Mode | Config | Notes |
|------|--------|-------|
| At-most-once | Auto-ack on receive | Fast; messages lost on consumer crash |
| At-least-once | Manual ack after processing | Duplicates on crash; idempotent consumer required |
| Publisher-side durability | Quorum queue + publisher confirms | Message safe after quorum writes to disk |

There is no exactly-once in AMQP 0-9-1. Design consumers to be idempotent. Cross-broker idempotency pattern: see parent `SKILL.md`.

## Failure handling (DLX/retry)

### Dead Letter Exchange (DLX) pattern

```
Main Queue → Consumer → [basicNack requeue=false] → DLX → DLQ
```

```java
// DLX exchange (direct or fanout)
@Bean Exchange dlxExchange() { return new DirectExchange("dlx.events", true, false); }

@Bean Queue deadLetterQueue() { return QueueBuilder.durable("orders.dlq").quorum().build(); }

@Bean Binding dlxBinding(Queue deadLetterQueue, DirectExchange dlxExchange) {
    return BindingBuilder.bind(deadLetterQueue).to(dlxExchange).with("order.created.dlq");
}
```

### Retry with per-message TTL (delayed requeue)

```
Main Queue → [fail] → DLX (no consumers) Queue with TTL → TTL expires → rerouted back to Main Queue
```

- Set `x-message-ttl` on the retry queue for backoff duration
- Set `x-dead-letter-exchange` on the retry queue back to the main exchange
- Each retry cycle increments attempt count (custom header); route to DLQ after max attempts

DLQ processor responsibilities: see parent `SKILL.md`.

## Schema & evolution

- Include `eventId`, `eventType`, `version` in message envelope (see parent `SKILL.md`)
- Use message properties: `contentType: application/json`, `messageId: <eventId>`
- For schema validation, use JSON Schema or Avro with a custom message converter
- No built-in schema registry — use Confluent Schema Registry via HTTP if strict compatibility needed

## Operational concerns

### Quorum queues vs classic queues

| | Quorum | Classic |
|--|--------|---------|
| **Durability** | Replicated Raft consensus | Mirrored (deprecated) or single node |
| **Failure safety** | Survives minority node failures | Mirror queues can lose data on failure |
| **Publisher confirms** | Required; sent after quorum fsync | Optional; sent on memory batch (transient) or disk write (durable) |
| **Use** | All new production queues | Legacy or non-critical workloads |
| **Limitations** | No per-message priority, no lazy mode | N/A |

**Use quorum queues for all new production deployments.** Classic mirrored queues are deprecated.

### RabbitMQ Streams

For use cases requiring replay (Kafka-like): the Streams plugin adds a persistent, replayable log per stream. Consumers specify an offset to start from (`first`, `last`, `offset N`, `timestamp`). Use when:
- Multiple independent consumers need to replay from different points
- High-throughput append-only log within RabbitMQ infrastructure
- Docs: https://www.rabbitmq.com/docs/streams

### Clustering
- Quorum queues distribute replicas automatically across cluster nodes
- Recommend ≥3 nodes for quorum (tolerates 1 failure)
- Use `cluster_formation.peer_discovery_backend = rabbit_peer_discovery_k8s` for Kubernetes

### Monitoring
- Queue depth (`messages_ready`, `messages_unacknowledged`)
- Consumer count per queue (alert on 0)
- Publish/ack rates for throughput baseline
- `memory_alarm` and `disk_free_alarm` for backpressure detection

## Common pitfalls

1. **Classic mirrored queues in new deployments** — use quorum queues. Mirrored queues are deprecated in RabbitMQ 3.13+.
2. **Auto-ack with manual processing** — message is acked on delivery, not after processing. Consumer crash loses the message. Use manual ack.
3. **Hot requeue loop** — `basicNack(requeue=true)` on a persistent error requeues infinitely, blocking the queue. Use DLX after max retries instead.
4. **No publisher confirms** — fire-and-forget on quorum queues loses messages silently. Always enable and handle confirms.
5. **Prefetch = 1 for high-throughput** — fine for fairness but limits throughput. Tune per consumer workload.
6. **Missing DLX binding** — rejected messages (`requeue=false`) with no DLX configured are silently discarded. Always configure DLX in production.
7. **Unbounded in-flight with `basicNack(requeue=true)`** — concurrent requeues pile up. Apply exponential backoff via TTL retry queues.
