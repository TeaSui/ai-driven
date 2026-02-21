export const QUEUES = {
  // Auth queues
  AUTH_EVENTS: 'auth.events',

  // Workflow queues
  WORKFLOW_EXECUTE: 'workflow.execute',
  WORKFLOW_EVENTS: 'workflow.events',
  WORKFLOW_STEP_EXECUTE: 'workflow.step.execute',

  // Integration queues
  INTEGRATION_ACTION: 'integration.action',
  INTEGRATION_EVENTS: 'integration.events',

  // Notification queues
  NOTIFICATION_SEND: 'notification.send',
  NOTIFICATION_EVENTS: 'notification.events',
} as const;

export const EXCHANGES = {
  AUTH: 'auth.exchange',
  WORKFLOW: 'workflow.exchange',
  INTEGRATION: 'integration.exchange',
  NOTIFICATION: 'notification.exchange',
  DEAD_LETTER: 'dead.letter.exchange',
} as const;

export const ROUTING_KEYS = {
  AUTH_USER_REGISTERED: 'auth.user.registered',
  AUTH_USER_UPDATED: 'auth.user.updated',
  WORKFLOW_EXECUTION_STARTED: 'workflow.execution.started',
  WORKFLOW_EXECUTION_COMPLETED: 'workflow.execution.completed',
  WORKFLOW_EXECUTION_FAILED: 'workflow.execution.failed',
  INTEGRATION_ACTION_EXECUTE: 'integration.action.execute',
  NOTIFICATION_SEND_EMAIL: 'notification.send.email',
  NOTIFICATION_SEND_SLACK: 'notification.send.slack',
} as const;
