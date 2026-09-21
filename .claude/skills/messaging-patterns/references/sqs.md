---
name: messaging-patterns-sqs
description: >
  AWS SQS implementation reference: standard vs FIFO queues, visibility timeout tuning, redrive policies,
  batching, long polling, partial batch failures in Lambda, message deduplication, cost model.
type: reference
---

# AWS SQS Reference

## When to use

SQS over Kafka/RabbitMQ when: AWS-native serverless stack (Lambda), simple task queues without replay, low operational overhead, tight integration with SNS/EventBridge, or cost-sensitive low-volume workloads.

Cross-broker selection guide: see parent `SKILL.md`.

## Core concepts

| Concept | Description |
|---------|-------------|
| **Standard queue** | At-least-once, best-effort ordering, nearly unlimited throughput |
| **FIFO queue** | Exactly-once (within deduplication window), strict ordering per message group, 300 TPS (3000 with batching) |
| **Visibility timeout** | Duration message is hidden after receive; consumer must delete before expiry or it reappears |
| **Redrive policy** | Move messages to DLQ after `maxReceiveCount` failed deliveries |
| **Message group ID** | FIFO only; messages in same group are ordered and processed serially |
| **Deduplication ID** | FIFO only; deduplicates within 5-minute window (content-based or explicit) |
| **Long polling** | `WaitTimeSeconds=20`; reduces empty receives and cost |

## Producer patterns

### Send single message (AWS SDK v3, TypeScript)

```typescript
import { SQSClient, SendMessageCommand } from "@aws-sdk/client-sqs";

const sqs = new SQSClient({ region: "ap-southeast-1" });

await sqs.send(new SendMessageCommand({
  QueueUrl: process.env.ORDER_QUEUE_URL,
  MessageBody: JSON.stringify({
    eventId: crypto.randomUUID(),
    eventType: "OrderCreated",
    version: 1,
    timestamp: new Date().toISOString(),
    data: order,
  }),
  // FIFO only:
  MessageGroupId: order.id,            // ordering scope
  MessageDeduplicationId: eventId,     // idempotent send (5-min window)
  // Standard queue — optional delay:
  DelaySeconds: 0,
}));
```

### Batch send (up to 10 messages, max 256 KB total)

```typescript
await sqs.send(new SendMessageBatchCommand({
  QueueUrl: process.env.ORDER_QUEUE_URL,
  Entries: orders.map((order, i) => ({
    Id: String(i),                      // batch-local ID for result correlation
    MessageBody: JSON.stringify(order),
    MessageGroupId: order.id,           // FIFO
    MessageDeduplicationId: order.eventId,
  })),
}));
```

Batch send costs 1 API call per batch (vs 10 for individual sends). Always use batch when sending multiple messages.

### Java producer (AWS SDK v2)

```java
sqsClient.sendMessage(SendMessageRequest.builder()
    .queueUrl(queueUrl)
    .messageBody(objectMapper.writeValueAsString(event))
    .messageGroupId(order.getId())             // FIFO
    .messageDeduplicationId(event.getEventId())
    .build());
```

## Consumer patterns

### Node.js consumer (BBC sqs-consumer)

```typescript
import { Consumer } from "sqs-consumer";

const consumer = Consumer.create({
  queueUrl: process.env.ORDER_QUEUE_URL,
  batchSize: 10,
  visibilityTimeout: 60,       // must exceed max processing time
  heartbeatInterval: 15,       // extend visibility every 15s for long jobs
  waitTimeSeconds: 20,         // long polling

  handleMessageBatch: async (messages) => {
    const results = await Promise.allSettled(
      messages.map(msg => processMessage(JSON.parse(msg.Body)))
    );
    // Return only successfully processed messages for deletion
    return messages.filter((_, i) => results[i].status === "fulfilled");
  },
});

consumer.on("error", (err) => log.error("SQS consumer error", err));
consumer.start();
```

Throwing from `handleMessage` / returning a rejected promise leaves the message on the queue (it reappears after visibility timeout). Return only the messages you want deleted.

### Lambda consumer — partial batch failure

When SQS triggers Lambda, configure `FunctionResponseTypes: ["ReportBatchItemFailures"]`:

```typescript
// Lambda handler
export const handler: SQSHandler = async (event) => {
  const failures: { itemIdentifier: string }[] = [];

  await Promise.all(event.Records.map(async (record) => {
    try {
      await processRecord(JSON.parse(record.body));
    } catch (err) {
      log.error("Failed record", { messageId: record.messageId, err });
      failures.push({ itemIdentifier: record.messageId });
    }
  }));

  // Return failed message IDs — SQS retries only those, not the whole batch
  return { batchItemFailures: failures };
};
```

Without `ReportBatchItemFailures`, a single failure retries the entire batch — successful messages are reprocessed.

### Java Lambda consumer (Spring Cloud Function / AWS Lambda Java runtime)

```java
public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
    List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
    for (SQSMessage msg : event.getRecords()) {
        try {
            process(msg);
        } catch (Exception e) {
            failures.add(SQSBatchResponse.BatchItemFailure.builder()
                .withItemIdentifier(msg.getMessageId()).build());
        }
    }
    return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
}
```

## Delivery guarantees

| Queue type | Delivery | Ordering |
|------------|----------|---------|
| Standard | At-least-once | Best-effort (not guaranteed) |
| FIFO | Exactly-once within 5-min deduplication window | Strict per message group |

**Standard queues are not ordered.** For ordered processing, use FIFO with `MessageGroupId`.  
**Idempotent consumers are still required for FIFO** — the deduplication window is 5 minutes; after that, duplicate sends are possible.

Cross-broker idempotency pattern: see parent `SKILL.md`.

## Failure handling (DLQ/retry)

### Redrive policy (CDK)

```typescript
const dlq = new sqs.Queue(this, "OrderDlq", {
  retentionPeriod: Duration.days(14),
  encryption: sqs.QueueEncryption.KMS_MANAGED,
});

const queue = new sqs.Queue(this, "OrderQueue", {
  visibilityTimeout: Duration.seconds(60),
  deadLetterQueue: {
    queue: dlq,
    maxReceiveCount: 5,     // move to DLQ after 5 failed deliveries
  },
  encryption: sqs.QueueEncryption.KMS_MANAGED,
});
```

`maxReceiveCount` = total delivery attempts before DLQ. Set to match your retry strategy (e.g., 4 retries + 1 initial = `maxReceiveCount: 5`).

### Visibility timeout sizing

`visibilityTimeout > (max_processing_time + buffer)`

- If Lambda timeout is 30 s, set visibility to ≥ 60 s
- Use `heartbeatInterval` (sqs-consumer) or `ChangeMessageVisibility` API to extend for long jobs
- If visibility expires before delete, message reappears — causes duplicate processing

### DLQ alarm (CloudWatch)

```typescript
dlq.metricNumberOfMessagesSent().createAlarm(this, "DlqAlarm", {
  threshold: 1,
  evaluationPeriods: 1,
  alarmDescription: "Messages arriving in DLQ",
});
```

DLQ processor responsibilities: see parent `SKILL.md`.

## Schema & evolution

- No built-in schema registry — use JSON with envelope (see parent `SKILL.md`)
- Add `MessageAttribute` for content type / schema version for routing without body parsing:
  ```typescript
  MessageAttributes: {
    "schema-version": { DataType: "Number", StringValue: "2" },
    "event-type": { DataType: "String", StringValue: "OrderCreated" },
  }
  ```
- For strict schema enforcement, route through EventBridge (supports JSON Schema validation) before SQS

## Operational concerns

### Cost model
- **Per request**: $0.40 per million requests (standard), $0.50/million (FIFO)
- Long polling reduces empty receive requests → direct cost saving; always use `WaitTimeSeconds=20`
- Batch up to 10 messages per send/receive/delete API call — 10× cost reduction vs individual calls
- Data transfer within same region: free

### Standard vs FIFO selection
| Need | Queue type |
|------|-----------|
| Max throughput, ordering not required | Standard |
| Per-entity ordered processing (e.g., user session events) | FIFO with `MessageGroupId = entityId` |
| Global ordering across all messages | FIFO with single `MessageGroupId` (serialises all processing) |
| Exactly-once within 5 minutes | FIFO with explicit `MessageDeduplicationId` |

### SNS → SQS fan-out
Publish to SNS topic; multiple SQS queues subscribe (each consumer gets its own copy):
```typescript
snsTopic.addSubscription(new subs.SqsSubscription(queue, {
  filterPolicy: {
    eventType: sns.SubscriptionFilter.stringFilter({ allowlist: ["OrderCreated"] }),
  },
}));
```

### Lambda trigger configuration
```typescript
fn.addEventSource(new SqsEventSource(queue, {
  batchSize: 10,
  maxBatchingWindow: Duration.seconds(5),   // wait up to 5s to fill batch
  reportBatchItemFailures: true,             // enable partial batch failure
}));
```

## Common pitfalls

1. **Visibility timeout too short** — message reappears mid-processing, causing duplicate processing. Set visibility > max processing time + safety buffer.
2. **Not using `ReportBatchItemFailures` with Lambda** — single failure retries entire batch. All successfully processed messages run again.
3. **FIFO with single `MessageGroupId`** — serializes all messages to one consumer, destroying throughput. Use entity ID as group ID.
4. **Standard queue for ordered workflows** — best-effort ordering is not guaranteed ordering. Use FIFO or design for any-order processing.
5. **Short polling** — `WaitTimeSeconds=0` hammers the API with empty receives and increases cost. Always use long polling (`WaitTimeSeconds=20`).
6. **`maxReceiveCount=1`** — any transient error sends to DLQ immediately. Set to allow ≥3 retries.
7. **No DLQ alarm** — messages silently pile up. Alert on `NumberOfMessagesSent` to DLQ ≥ 1.
8. **Message body > 256 KB** — SQS hard limit. Store payload in S3 and send a pointer. Use the [Extended Client Library](https://github.com/awslabs/amazon-sqs-java-extended-client-lib) for transparent handling.
