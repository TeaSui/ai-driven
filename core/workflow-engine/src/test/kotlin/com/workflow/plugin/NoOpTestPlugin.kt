package com.workflow.plugin

import com.workflow.api.*
import com.workflow.tenant.TenantConfiguration

/**
 * No-op plugin for testing the workflow engine.
 */
class NoOpTestPlugin : AbstractWorkflowPlugin() {
    override val pluginId: String = "noop-plugin"
    override val displayName: String = "No-Op Test Plugin"
    override val version: String = "1.0.0"

    override val supportedActions: List<ActionDescriptor> = listOf(
        ActionDescriptor(
            id = "noop",
            displayName = "No Operation",
            description = "Does nothing, always succeeds"
        )
    )

    override fun doExecute(
        action: String,
        step: StepDefinition,
        context: ExecutionContext,
        tenantConfig: TenantConfiguration
    ): StepResult = successResult(step, mapOf("noop" to true))
}
