import amqplib, { Channel, Connection } from 'amqplib';
import { Logger } from '../utils/logger';

const logger = new Logger('integration-service:rabbitmq');
let connection: Connection | null = null;
let channel: Channel | null = null;
const RABBITMQ_URL = process.env.RABBITMQ_URL || 'amqp://admin:admin@localhost:5672';
const EXCHANGE = 'integration.exchange';

export async function connectRabbitMQ(): Promise<void> {
  connection = await amqplib.connect(RABBITMQ_URL);
  channel = await connection.createChannel();
  await channel.assertExchange(EXCHANGE, 'topic', { durable: true });
  connection.on('error', (err) => logger.error('RabbitMQ error', err));
  logger.info('RabbitMQ connected');
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
