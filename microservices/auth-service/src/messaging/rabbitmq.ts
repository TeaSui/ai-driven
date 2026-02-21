import amqplib, { Channel, Connection } from 'amqplib';
import { Logger } from '../utils/logger';

const logger = new Logger('auth-service:rabbitmq');

let connection: Connection | null = null;
let channel: Channel | null = null;

const RABBITMQ_URL = process.env.RABBITMQ_URL || 'amqp://admin:admin@localhost:5672';
const EXCHANGE = 'auth.exchange';

export async function connectRabbitMQ(): Promise<void> {
  try {
    connection = await amqplib.connect(RABBITMQ_URL);
    channel = await connection.createChannel();

    await channel.assertExchange(EXCHANGE, 'topic', { durable: true });

    connection.on('error', (err) => {
      logger.error('RabbitMQ connection error', err);
      reconnect();
    });

    connection.on('close', () => {
      logger.warn('RabbitMQ connection closed, reconnecting...');
      reconnect();
    });

    logger.info('RabbitMQ connected', { exchange: EXCHANGE });
  } catch (error) {
    logger.error('Failed to connect to RabbitMQ', error as Error);
    throw error;
  }
}

async function reconnect(): Promise<void> {
  setTimeout(async () => {
    try {
      await connectRabbitMQ();
    } catch {
      reconnect();
    }
  }, 5000);
}

export async function publishEvent(
  routingKey: string,
  payload: Record<string, unknown>
): Promise<void> {
  if (!channel) {
    logger.warn('RabbitMQ channel not available, skipping event publish', { routingKey });
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

  logger.debug('Event published', { routingKey });
}
