import { v4 as uuidv4 } from 'uuid';

export interface BaseEvent {
  eventId: string;
  eventType: string;
  tenantId: string;
  timestamp: string;
  version: string;
  correlationId?: string;
  causationId?: string;
  metadata?: Record<string, unknown>;
}

export function createEvent<T extends Omit<BaseEvent, 'eventId' | 'timestamp' | 'version'>>(
  event: T
): T & { eventId: string; timestamp: string; version: string } {
  return {
    ...event,
    eventId: uuidv4(),
    timestamp: new Date().toISOString(),
    version: '1.0',
  };
}
