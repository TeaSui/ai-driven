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
| **FIFO queue** | Producer deduplication within five minutes; ordering per message group; throughput depends on mode/Region/quota |
| **Visibility timeout** | Duration message is hidden after receive; consumer must delete before expiry or it reappears |
| **Redrive policy** | Move messages to DLQ after `maxReceiveCount` failed deliveries |
| **Message group ID** | FIFO only; messages in same group are ordered and processed serially |
| **Deduplication ID** | FIFO only; deduplicates within 5-minute window (content-based or explicit) |
| **Long polling** | `WaitTimeSeconds=20`; reduces empty receives and cost |

## Producer patterns

### Send single message (AWS SDK v3, TypeScript)

```typescript
import { SQSClient, SendMessageCommand } from "@aws-sdk/client-sqs";
import { randomUUID } from "node:crypto";

const sqs = new SQSClient({ region: "ap-southeast-1" });

// For a critical business event, reuse the eventId from its committed outbox row.
const eventId = randomUUID(); // direct-send example only: create once, reuse on retry
await sqs.send(new SendMessageCommand({
  QueueUrl: process.env.ORDER_QUEUE_URL,
  MessageBody: JSON.stringify({
    eventId,
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

### Batch send (up to 10 messages, max 1 MiB total)

```typescript
const response = await sqs.send(new SendMessageBatchCommand({
  QueueUrl: process.env.ORDER_QUEUE_URL,
  Entries: orders.map((order, i) => ({
    Id: String(i),                      // batch-local ID for result correlation
    MessageBody: JSON.stringify({ eventId: order.eventId, eventType: "OrderCreated", version: 1, data: order }),
    MessageGroupId: order.id,           // FIFO
    MessageDeduplicationId: order.eventId,
  })),
}));
```

Inspect `response.Failed` even on HTTP 200; retry failed entries with their unchanged event IDs. Critical events must already exist in a committed outbox, and are marked published only after success. Each individual message and the total batch payload must fit 1 MiB (1,048,576 bytes); retain any stricter project payload policy. See [SendMessageBatch](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_SendMessageBatch.html).

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

### Standard-queue Node.js consumer (BBC sqs-consumer)

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
      messages.map(msg => inboxHandler.applyOnce(JSON.parse(msg.Body!)))
    );
    // applyOnce resolves only AFTER inbox + database effects commit.
    // Return committed successes/duplicates for deletion; failed messages remain.
    return messages.filter((_, i) => results[i].status === "fulfilled");
  },
});

consumer.on("error", () => log.error("SQS consumer error", { category: "consumer_failed" }));
consumer.start();
```

`inboxHandler.applyOnce` is an application-owned implementation of the parent skill's transactional inbox contract, not an SDK API. Return only committed successes or committed duplicates for deletion. A rejection must leave that message retryable. Match return/ack options to the installed `sqs-consumer` release and [its documented acknowledgement behavior](https://bbc.github.io/sqs-consumer/); do not enable unconditional deletion. Parallel processing here is for Standard queues. FIFO consumers must serialize each message group and stop that group on failure; do not use this parallel sample for FIFO ordering.

### Standard-queue Lambda consumer — partial batch failure

When SQS triggers Lambda, configure `FunctionResponseTypes: ["ReportBatchItemFailures"]`:

```typescript
// Lambda handler
export const handler: SQSHandler = async (event) => {
  const failures: { itemIdentifier: string }[] = [];

  await Promise.all(event.Records.map(async (record) => {
    try {
      await inboxHandler.applyOnce(JSON.parse(record.body)); // resolves only after DB commit
    } catch (err) {
      log.error("Failed record", { messageId: record.messageId, category: "processing_failed" });
      failures.push({ itemIdentifier: record.messageId });
    }
  }));

  // Return failed message IDs — SQS retries only those, not the whole batch
  return { batchItemFailures: failures };
};
```

Without `ReportBatchItemFailures`, a single failure retries the entire batch. A crash after database commit/before returning success also redelivers: the inbox must suppress committed duplicates. For FIFO, stop after the first failure and return both failed and unprocessed message IDs to preserve ordering; do not use the parallel Standard-queue handler. See [Lambda partial batch guidance](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-errorhandling.html).

### Java Lambda consumer (Spring Cloud Function / AWS Lambda Java runtime)

```java
public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
    List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
    for (SQSMessage msg : event.getRecords()) {
        try {
            inboxHandler.applyOnce(msg); // returns only after inbox + DB effects commit
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
| FIFO | Send deduplication within 5-min window; processing can redeliver until delete | Strict per message group with ordered consumer handling |

**Standard queues are not ordered.** For ordered processing, use FIFO with `MessageGroupId`.  
**Idempotent consumers are still required for FIFO** — send deduplication does not atomically commit application effects and message deletion. Visibility expiration or a crash after commit can cause repeated processing even inside five minutes.

Cross-broker idempotency pattern: see parent `SKILL.md`.

## Failure handling (DLQ/retry)

### Redrive policy (CDK)

```typescript
const dlq = new sqs.Queue(this, "OrderDlq", {
  retentionPeriod: Duration.days(14),
  encryption: sqs.QueueEncryption.KMS_MANAGED,
});

const queue = new sqs.Queue(this, "OrderQueue", {
  visibilityTimeout: Duration.seconds(185), // example: Lambda timeout 30s + batch window 5s
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

- For Lambda, use visibility ≥ 6 × function timeout + batching window (30 s + a 5 s batch window → ≥185 s). For other consumers, size from maximum processing time plus retry/scheduling buffer.
- Use `heartbeatInterval` (sqs-consumer) or `ChangeMessageVisibility` API to extend for long jobs
- If visibility expires before delete, message reappears — causes duplicate processing
- See [Lambda event source configuration](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-configure.html); do not copy a general-worker visibility value into a Lambda integration.

### DLQ alarm (CloudWatch)

```typescript
dlq.metricApproximateNumberOfMessagesVisible().createAlarm(this, "DlqAlarm", {
  threshold: 1,
  evaluationPeriods: 1,
  comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
  alarmDescription: "Messages available in DLQ",
});
```

Import `cloudwatch` from `aws-cdk-lib/aws-cloudwatch` in this CDK v2 fragment. Visible depth is an approximate gauge; choose an evaluation period and missing-data treatment for the project's alert policy. `NumberOfMessagesSent` excludes automatic redrive into a DLQ. See [SQS metrics](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-available-cloudwatch-metrics.html). DLQ processor responsibilities: see parent `SKILL.md`.

## Schema & evolution

- No built-in schema registry — use JSON with envelope (see parent `SKILL.md`)
- Add `MessageAttribute` for content type / schema version for routing without body parsing:
  ```typescript
  MessageAttributes: {
    "schema-version": { DataType: "Number", StringValue: "2" },
    "event-type": { DataType: "String", StringValue: "OrderCreated" },
  }
  ```
- Validate event schemas in the producer/consumer using the project's installed schema validator; registry/discovery alone is not runtime enforcement.

## Operational concerns

### Cost model
- Verify current regional rates and payload/request billing before estimating costs: [SQS pricing](https://aws.amazon.com/sqs/pricing/).
- Long polling reduces empty receives; batch up to 10 compatible messages while respecting payload limits. Savings depend on payload size and utilization.

### Standard vs FIFO selection
| Need | Queue type |
|------|-----------|
| Max throughput, ordering not required | Standard |
| Per-entity ordered processing (e.g., user session events) | FIFO with `MessageGroupId = entityId` |
| Global ordering across all messages | FIFO with single `MessageGroupId` (serialises all processing) |
| Deduplicate producer retries within 5 minutes | FIFO with stable `MessageDeduplicationId`; consumer inbox still required |

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

1. **Visibility timeout too short** — size from worker processing time; for Lambda apply the six-times timeout plus batching-window guidance above.
2. **Not using `ReportBatchItemFailures` with Lambda** — single failure retries entire batch. All successfully processed messages run again.
3. **FIFO with single `MessageGroupId`** — serializes all messages to one consumer, destroying throughput. Use entity ID as group ID.
4. **Standard queue for ordered workflows** — best-effort ordering is not guaranteed ordering. Use FIFO or design for any-order processing.
5. **Short polling** — `WaitTimeSeconds=0` hammers the API with empty receives and increases cost. Always use long polling (`WaitTimeSeconds=20`).
6. **`maxReceiveCount=1`** — any transient error sends to DLQ immediately. Set to allow ≥3 retries.
7. **No DLQ depth alarm** — alert on `ApproximateNumberOfMessagesVisible` ≥1; sent count misses automatic redrive.
8. **Message body > 1 MiB** — SQS hard limit (batch total has the same ceiling). Store payload in S3 and send a pointer. Use the [Extended Client Library](https://github.com/awslabs/amazon-sqs-java-extended-client-lib) for transparent handling.
