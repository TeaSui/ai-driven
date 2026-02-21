import { BaseEvent } from './base.event';
import { UserRole } from '../dtos/auth.dto';

export const AUTH_EVENTS = {
  USER_REGISTERED: 'auth.user.registered',
  USER_LOGGED_IN: 'auth.user.logged_in',
  USER_LOGGED_OUT: 'auth.user.logged_out',
  USER_UPDATED: 'auth.user.updated',
  USER_DEACTIVATED: 'auth.user.deactivated',
  PASSWORD_CHANGED: 'auth.password.changed',
  TOKEN_REFRESHED: 'auth.token.refreshed',
} as const;

export type AuthEventType = (typeof AUTH_EVENTS)[keyof typeof AUTH_EVENTS];

export interface UserRegisteredEvent extends BaseEvent {
  eventType: typeof AUTH_EVENTS.USER_REGISTERED;
  payload: {
    userId: string;
    email: string;
    firstName: string;
    lastName: string;
    role: UserRole;
    tenantId: string;
  };
}

export interface UserLoggedInEvent extends BaseEvent {
  eventType: typeof AUTH_EVENTS.USER_LOGGED_IN;
  payload: {
    userId: string;
    email: string;
    tenantId: string;
    ipAddress?: string;
    userAgent?: string;
  };
}

export interface UserUpdatedEvent extends BaseEvent {
  eventType: typeof AUTH_EVENTS.USER_UPDATED;
  payload: {
    userId: string;
    changes: Record<string, unknown>;
    tenantId: string;
  };
}

export interface UserDeactivatedEvent extends BaseEvent {
  eventType: typeof AUTH_EVENTS.USER_DEACTIVATED;
  payload: {
    userId: string;
    tenantId: string;
    reason?: string;
  };
}
