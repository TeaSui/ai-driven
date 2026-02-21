import amqplib, { Channel, Connection } from 'amqplib';
import { Logger } from '../utils/logger';
import { NotificationService } from '../services/notification.service';

const logger = new Logger('notification-service:rabbitmq');
let connection: Connection | null = null;
let channel: Channel | null = null;
const RABBITMQ_URL = process.env.RABBITMQ_URL || 'amqp://admin:admin@localhost:5672';
const EXCHANGE = 'notification.exchange';
const QUEUE = 'notification.send';

export async function connectRabbitMQ(): Promise<void> {
  connection = await amqplib.connect(RABBITMQ_URL);
  channel = await connection.createChannel();
  await channel.assertExchange(EXCHANGE, 'topic', { durable: true });
  await channel.assertQueue(QUEUE, { durable: true });
  connection.on('error', (err) => logger.error('RabbitMQ error', err));
  logger.info('RabbitMQ connected');
}

export async function startConsumers(): Promise<void> {
  if (!channel) throw new Error('RabbitMQ channel not initialized');

  const notificationService = new NotificationService();

  // Bind to workflow events to auto-send notifications
  await channel.assertExchange('workflow.exchange', 'topic', { durable: true });
  await channel.bindQueue(QUEUE, 'workflow.exchange', 'workflow.execution.failed');
  await channel.bindQueue(QUEUE, 'workflow.exchange', 'workflow.execution.completed');

  channel.consume(QUEUE, async (msg) => {
    if (!msg) return;
    try {
      const event = JSON.parse(msg.content.toString());
      logger.debug('Received event for notification', { eventType: event.eventType });

      if (event.eventType === 'workflow.execution.failed') {
        await notificationService.send({
          type: 'WORKFLOW_FAILED',
          channel: 'EMAIL',
          recipient: event.payload.triggeredBy || 'admin@example.com',
          subject: `Workflow Failed: ${event.payload.workflowName}`,
          body: `<p>Workflow <strong>${event.payload.workflowName}</strong> failed.</p><p>Error: ${event.payload.error}</p>`,
          tenantId: event.payload.tenantId,
          metadata: { executionId: event.payload.executionId },
        });
      }

      channel!.ack(msg);
    } catch (error) {
      logger.error('Failed to process notification event', error as Error);
      channel!.nack(msg, false, false);
    }
  });
}

export async function publishEvent(
  routingKey: string,
  payload: Record<string, unknown>
): Promise<void> {
  if (!channel) return;
  const message = JSON.stringify({
    eventId: require('uuid').v4(),
    eventType: routingKey,
    timestamp: new Date().toISOString(),
    version: '1.0',
    payload,
  });
  channel.publish(EXCHANGE, routingKey, Buffer.from(message), {
    persistent: true,
    contentType: 'application/json',
  });
}
