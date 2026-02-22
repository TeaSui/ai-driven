package com.workflow.plugin.email

import com.workflow.api.ExecutionContext
import com.workflow.api.StepDefinition
import com.workflow.api.StepResult
import com.workflow.plugin.*
import com.workflow.tenant.TenantConfiguration

/**
 * Email plugin for sending emails as workflow steps.
 * Supports SMTP and API-based email providers.
 */
class EmailPlugin(
    private val emailSender: EmailSender
) : AbstractWorkflowPlugin() {

    override val pluginId: String = "email-plugin"
    override val displayName: String = "Email"
    override val version: String = "1.0.0"

    override val supportedActions: List<ActionDescriptor> = listOf(
        ActionDescriptor(
            id = "send",
            displayName = "Send Email",
            description = "Send an email to one or more recipients",
            requiredConfig = listOf(
                ConfigField("to", FieldType.EMAIL, "Recipient email address"),
                ConfigField("subject", FieldType.STRING, "Email subject"),
                ConfigField("body", FieldType.STRING, "Email body (supports {{variable}} templates)")
            ),
            optionalConfig = listOf(
                ConfigField("cc", FieldType.EMAIL, "CC recipients"),
                ConfigField("bcc", FieldType.EMAIL, "BCC recipients"),
                ConfigField("from", FieldType.EMAIL, "Sender email address"),
                ConfigField("replyTo", FieldType.EMAIL, "Reply-to address"),
                ConfigField("htmlBody", FieldType.STRING, "HTML email body")
            )
        ),
        ActionDescriptor(
            id = "send-template",
            displayName = "Send Template Email",
            description = "Send an email using a predefined template",
            requiredConfig = listOf(
                ConfigField("to", FieldType.EMAIL, "Recipient email address"),
                ConfigField("templateId", FieldType.STRING, "Template identifier")
            ),
            optionalConfig = listOf(
                ConfigField("templateData", FieldType.JSON, "Template variables as JSON")
            )
        )
    )

    override fun doExecute(
        action: String,
        step: StepDefinition,
        context: ExecutionContext,
        tenantConfig: TenantConfiguration
    ): StepResult {
        return when (action) {
            "send" -> sendEmail(step, context, tenantConfig)
            "send-template" -> sendTemplateEmail(step, context, tenantConfig)
            else -> failResult(step, "Unknown action: $action")
        }
    }

    private fun sendEmail(
        step: StepDefinition,
        context: ExecutionContext,
        tenantConfig: TenantConfiguration
    ): StepResult {
        val to = resolveTemplate(getRequiredConfig(step, "to"), context)
        val subject = resolveTemplate(getRequiredConfig(step, "subject"), context)
        val body = resolveTemplate(getRequiredConfig(step, "body"), context)
        val from = getOptionalConfig(step, "from",
            tenantConfig.getIntegration("email")?.getOptional("defaultFrom") ?: "noreply@workflow.com")

        val message = EmailMessage(
            to = listOf(to),
            from = from,
            subject = subject,
            textBody = body,
            htmlBody = getOptionalConfig(step, "htmlBody").ifBlank { null }
        )

        val result = emailSender.send(message)
        return if (result.success) {
            successResult(step, mapOf("messageId" to (result.messageId ?: "")))
        } else {
            failResult(step, result.error ?: "Email send failed")
        }
    }

    private fun sendTemplateEmail(
        step: StepDefinition,
        context: ExecutionContext,
        tenantConfig: TenantConfiguration
    ): StepResult {
        val to = resolveTemplate(getRequiredConfig(step, "to"), context)
        val templateId = getRequiredConfig(step, "templateId")

        log.info("Sending template email '{}' to '{}'", templateId, to)

        val result = emailSender.sendTemplate(
            to = to,
            templateId = templateId,
            templateData = step.config.filterKeys { it == "templateData" }
        )

        return if (result.success) {
            successResult(step, mapOf("messageId" to (result.messageId ?: ""), "templateId" to templateId))
        } else {
            failResult(step, result.error ?: "Template email send failed")
        }
    }

    override fun validate(step: StepDefinition, tenantConfig: TenantConfiguration): ValidationResult {
        val errors = mutableListOf<String>()
        val action = step.actionId

        when (action) {
            "send" -> {
                if (step.config["to"] == null) errors.add("'to' is required for send action")
                if (step.config["subject"] == null) errors.add("'subject' is required for send action")
                if (step.config["body"] == null) errors.add("'body' is required for send action")
            }
            "send-template" -> {
                if (step.config["to"] == null) errors.add("'to' is required for send-template action")
                if (step.config["templateId"] == null) errors.add("'templateId' is required for send-template action")
            }
        }

        return if (errors.isEmpty()) ValidationResult.valid() else ValidationResult.invalid(errors)
    }
}
