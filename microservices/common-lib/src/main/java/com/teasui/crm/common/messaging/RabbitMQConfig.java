package com.teasui.crm.common.messaging;

/**
 * Shared RabbitMQ exchange and routing key constants.
 * All services must use these constants for consistent messaging.
 */
public final class RabbitMQConfig {

    private RabbitMQConfig() {}

    // Exchanges
    public static final String WORKFLOW_EXCHANGE = "crm.workflow.exchange";
    public static final String AUTH_EXCHANGE = "crm.auth.exchange";
    public static final String NOTIFICATION_EXCHANGE = "crm.notification.exchange";
    public static final String INTEGRATION_EXCHANGE = "crm.integration.exchange";
    public static final String DEAD_LETTER_EXCHANGE = "crm.dlx.exchange";

    // Routing Keys - Workflow
    public static final String WORKFLOW_STARTED_KEY = "workflow.execution.started";
    public static final String WORKFLOW_COMPLETED_KEY = "workflow.execution.completed";
    public static final String WORKFLOW_FAILED_KEY = "workflow.execution.failed";
    public static final String WORKFLOW_STEP_COMPLETED_KEY = "workflow.step.completed";
    public static final String WORKFLOW_TRIGGER_KEY = "workflow.trigger";

    // Routing Keys - Auth
    public static final String USER_LOGIN_KEY = "auth.user.login";
    public static final String USER_LOGOUT_KEY = "auth.user.logout";
    public static final String USER_CREATED_KEY = "auth.user.created";
    public static final String USER_UPDATED_KEY = "auth.user.updated";

    // Routing Keys - Notification
    public static final String NOTIFICATION_EMAIL_KEY = "notification.email";
    public static final String NOTIFICATION_IN_APP_KEY = "notification.inapp";
    public static final String NOTIFICATION_WEBHOOK_KEY = "notification.webhook";

    // Routing Keys - Integration
    public static final String INTEGRATION_TRIGGER_KEY = "integration.trigger";
    public static final String INTEGRATION_RESULT_KEY = "integration.result";
    public static final String INTEGRATION_ERROR_KEY = "integration.error";

    // Queue Names
    public static final String WORKFLOW_QUEUE = "crm.workflow.queue";
    public static final String NOTIFICATION_EMAIL_QUEUE = "crm.notification.email.queue";
    public static final String NOTIFICATION_IN_APP_QUEUE = "crm.notification.inapp.queue";
    public static final String INTEGRATION_TRIGGER_QUEUE = "crm.integration.trigger.queue";
    public static final String AUTH_EVENT_QUEUE = "crm.auth.event.queue";
    public static final String DEAD_LETTER_QUEUE = "crm.dlx.queue";
}
