package com.workflow.plugin.slack

import com.workflow.api.ExecutionContext
import com.workflow.api.StepDefinition
import com.workflow.api.StepResult
import com.workflow.api.TenantId
import com.workflow.plugin.*
import com.workflow.tenant.TenantConfiguration

/**
 * Slack plugin for sending messages and notifications via Slack.
 */
class SlackPlugin(
    private val slackClient: SlackClient
) : AbstractWorkflowPlugin() {

    override val pluginId: String = "slack-plugin"
    override val displayName: String = "Slack"
    override val version: String = "1.0.0"

    override val supportedActions: List<ActionDescriptor> = listOf(
        ActionDescriptor(
            id = "send-message",
            displayName = "Send Message",
            description = "Send a message to a Slack channel",
            requiredConfig = listOf(
                ConfigField("channel", FieldType.STRING, "Slack channel name or ID"),
                ConfigField("message", FieldType.STRING, "Message text (supports {{variable}} templates)")
            ),
            optionalConfig = listOf(
                ConfigField("username", FieldType.STRING, "Bot username override"),
                ConfigField("iconEmoji", FieldType.STRING, "Bot icon emoji")
            )
        ),
        ActionDescriptor(
            id = "send-alert",
            displayName = "Send Alert",
            description = "Send a formatted alert message to Slack",
            requiredConfig = listOf(
                ConfigField("channel", FieldType.STRING, "Slack channel name or ID"),
                ConfigField("title", FieldType.STRING, "Alert title"),
                ConfigField("message", FieldType.STRING, "Alert message")
            ),
            optionalConfig = listOf(
                ConfigField("severity", FieldType.STRING, "Alert severity: info, warning, error")
            )
        )
    )

    override fun doExecute(
        action: String,
        step: StepDefinition,
        context: ExecutionContext,
        tenantConfig: TenantConfiguration
    ): StepResult {
        val token = tenantConfig.getIntegration("slack")?.getRequired("botToken")
            ?: return failResult(step, "Slack integration not configured for tenant")

        return when (action) {
            "send-message" -> sendMessage(step, context, token)
            "send-alert" -> sendAlert(step, context, token)
            else -> failResult(step, "Unknown action: $action")
        }
    }

    private fun sendMessage(
        step: StepDefinition,
        context: ExecutionContext,
        token: String
    ): StepResult {
        val channel = getRequiredConfig(step, "channel")
        val message = resolveTemplate(getRequiredConfig(step, "message"), context)

        val result = slackClient.sendMessage(
            token = token,
            channel = channel,
            text = message
        )

        return if (result.ok) {
            successResult(step, mapOf("ts" to (result.ts ?: ""), "channel" to channel))
        } else {
            failResult(step, result.error ?: "Slack message failed")
        }
    }

    private fun sendAlert(
        step: StepDefinition,
        context: ExecutionContext,
        token: String
    ): StepResult {
        val channel = getRequiredConfig(step, "channel")
        val title = resolveTemplate(getRequiredConfig(step, "title"), context)
        val message = resolveTemplate(getRequiredConfig(step, "message"), context)
        val severity = getOptionalConfig(step, "severity", "info")

        val color = when (severity) {
            "error" -> "#FF0000"
            "warning" -> "#FFA500"
            else -> "#36A64F"
        }

        val result = slackClient.sendAttachment(
            token = token,
            channel = channel,
            title = title,
            text = message,
            color = color
        )

        return if (result.ok) {
            successResult(step, mapOf("ts" to (result.ts ?: ""), "channel" to channel))
        } else {
            failResult(step, result.error ?: "Slack alert failed")
        }
    }

    override fun onTenantInit(tenantId: TenantId, tenantConfig: TenantConfiguration) {
        val integration = tenantConfig.getIntegration("slack")
        if (integration != null) {
            log.info("Slack plugin initialized for tenant: {}", tenantId.value)
        } else {
            log.warn("Slack integration not configured for tenant: {}", tenantId.value)
        }
    }
}
