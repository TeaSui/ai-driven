import amqplib, { Channel, Connection } from 'amqplib';
import { Logger } from '../utils/logger';

const logger = new Logger('workflow-service:rabbitmq');

let connection: Connection | null = null;
let channel: Channel | null = null;

const RABBITMQ_URL = process.env.RABBITMQ_URL || 'amqp://admin:admin@localhost:5672';
const EXCHANGE = 'workflow.exchange';
const QUEUE = 'workflow.execute';

export async function connectRabbitMQ(): Promise<void> {
  connection = await amqplib.connect(RABBITMQ_URL);
  channel = await connection.createChannel();

  await channel.assertExchange(EXCHANGE, 'topic', { durable: true });
  await channel.assertQueue(QUEUE, { durable: true });

  connection.on('error', (err) => {
    logger.error('RabbitMQ connection error', err);
  });

  logger.info('RabbitMQ connected', { exchange: EXCHANGE });
}

export async function startConsumers(): Promise<void> {
  if (!channel) throw new Error('RabbitMQ channel not initialized');

  // Listen for workflow trigger events from other services
  await channel.bindQueue(QUEUE, 'auth.exchange', 'auth.user.registered');

  channel.consume(QUEUE, async (msg) => {
    if (!msg) return;
    try {
      const event = JSON.parse(msg.content.toString());
      logger.debug('Received event', { eventType: event.eventType });
      channel!.ack(msg);
    } catch (error) {
      logger.error('Failed to process message', error as Error);
      channel!.nack(msg, false, false);
    }
  });
}

export async function publishEvent(
  routingKey: string,
  payload: Record<string, unknown>
): Promise<void> {
  if (!channel) {
    logger.warn('RabbitMQ channel not available', { routingKey });
    return;
  }

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
