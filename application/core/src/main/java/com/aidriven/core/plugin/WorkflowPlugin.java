package com.aidriven.core.plugin;

import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.tenant.TenantConfig;

/**
 * Extension point for tenant-specific workflow customizations.
 *
 * <p>Plugins allow companies to inject custom logic at key points in the
 * workflow without modifying core code. Each plugin is registered per-tenant
 * in the {@link PluginRegistry}.</p>
 *
 * <p>Lifecycle hooks:
 * <ul>
 *   <li>{@link #onTicketReceived} — called when a webhook triggers the workflow</li>
 *   <li>{@link #onBeforeCodeGeneration} — called before Claude is invoked</li>
 *   <li>{@link #onAfterCodeGeneration} — called after code is generated</li>
 *   <li>{@link #onPrCreated} — called after a PR is created</li>
 * </ul>
 */
public interface WorkflowPlugin {

    /**
     * Returns the unique plugin identifier.
     */
    String pluginId();

    /**
     * Returns the tenant this plugin is registered for.
     * Return {@code null} to apply to all tenants (global plugin).
     */
    default String tenantId() {
        return null;
    }

    /**
     * Called when a Jira ticket is received and validated.
     * Can be used to enrich ticket data or perform pre-flight checks.
     *
     * @param ticket       The received ticket
     * @param tenantConfig The tenant configuration
     * @return Modified ticket (or the same instance if no changes)
     */
    default TicketInfo onTicketReceived(TicketInfo ticket, TenantConfig tenantConfig) {
        return ticket;
    }

    /**
     * Called before Claude is invoked for code generation.
     * Can be used to inject additional context or modify the prompt.
     *
     * @param context      The current workflow context
     * @param tenantConfig The tenant configuration
     * @return Modified context
     */
    default WorkflowContext onBeforeCodeGeneration(WorkflowContext context, TenantConfig tenantConfig) {
        return context;
    }

    /**
     * Called after code generation completes.
     * Can be used to validate or post-process generated files.
     *
     * @param context      The workflow context with generated files
     * @param tenantConfig The tenant configuration
     * @return Modified context
     */
    default WorkflowContext onAfterCodeGeneration(WorkflowContext context, TenantConfig tenantConfig) {
        return context;
    }

    /**
     * Called after a PR is successfully created.
     * Can be used to send notifications or update external systems.
     *
     * @param ticketKey    The Jira ticket key
     * @param prUrl        The URL of the created PR
     * @param tenantConfig The tenant configuration
     */
    default void onPrCreated(String ticketKey, String prUrl, TenantConfig tenantConfig) {
        // no-op by default
    }
}
