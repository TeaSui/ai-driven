package com.aidriven.spi;

import java.util.Map;

/**
 * Service Provider Interface for message queue operations.
 * Implementations can wrap AWS SQS, RabbitMQ, Kafka, etc.
 */
public interface QueueProvider {

    /**
     * Unique identifier for this provider.
     */
    String providerId();

    /**
     * Sends a message to a queue.
     *
     * @param queueId          Queue identifier (URL, name, or ARN)
     * @param messageBody      Message content
     * @param messageGroupId   Group ID for FIFO ordering (nullable for standard queues)
     * @param deduplicationId  Deduplication ID (nullable)
     * @return Message ID
     */
    String sendMessage(String queueId, String messageBody,
                       String messageGroupId, String deduplicationId) throws Exception;
}
