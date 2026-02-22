package com.workflow.plugin.webhook

import com.workflow.api.ExecutionContext
import com.workflow.api.StepDefinition
import com.workflow.api.StepResult
import com.workflow.plugin.*
import com.workflow.tenant.TenantConfiguration

/**
 * Webhook plugin for making HTTP calls as workflow steps.
 */
class WebhookPlugin(
    private val httpClient: HttpClient
) : AbstractWorkflowPlugin() {

    override val pluginId: String = "webhook-plugin"
    override val displayName: String = "Webhook"
    override val version: String = "1.0.0"

    override val supportedActions: List<ActionDescriptor> = listOf(
        ActionDescriptor(
            id = "post",
            displayName = "HTTP POST",
            description = "Send an HTTP POST request to a URL",
            requiredConfig = listOf(
                ConfigField("url", FieldType.URL, "Target URL")
            ),
            optionalConfig = listOf(
                ConfigField("headers", FieldType.JSON, "Request headers as JSON"),
                ConfigField("body", FieldType.JSON, "Request body as JSON"),
                ConfigField("timeoutMs", FieldType.NUMBER, "Request timeout in milliseconds")
            )
        ),
        ActionDescriptor(
            id = "get",
            displayName = "HTTP GET",
            description = "Send an HTTP GET request to a URL",
            requiredConfig = listOf(
                ConfigField("url", FieldType.URL, "Target URL")
            ),
            optionalConfig = listOf(
                ConfigField("headers", FieldType.JSON, "Request headers as JSON"),
                ConfigField("queryParams", FieldType.JSON, "Query parameters as JSON")
            )
        )
    )

    override fun doExecute(
        action: String,
        step: StepDefinition,
        context: ExecutionContext,
        tenantConfig: TenantConfiguration
    ): StepResult {
        val url = resolveTemplate(getRequiredConfig(step, "url"), context)
        val headers = buildHeaders(step, tenantConfig)
        val timeoutMs = getOptionalConfig(step, "timeoutMs", "5000").toLongOrNull() ?: 5000L

        return when (action) {
            "post" -> {
                val body = getOptionalConfig(step, "body")
                val resolvedBody = resolveTemplate(body, context)
                val response = httpClient.post(url, resolvedBody, headers, timeoutMs)
                handleResponse(step, response)
            }
            "get" -> {
                val response = httpClient.get(url, headers, timeoutMs)
                handleResponse(step, response)
            }
            else -> failResult(step, "Unknown action: $action")
        }
    }

    private fun buildHeaders(
        step: StepDefinition,
        tenantConfig: TenantConfiguration
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["Content-Type"] = "application/json"
        headers["X-Workflow-Tenant"] = tenantConfig.tenantId.value

        // Add step-level headers
        @Suppress("UNCHECKED_CAST")
        val stepHeaders = step.config["headers"] as? Map<String, String> ?: emptyMap()
        headers.putAll(stepHeaders)

        return headers
    }

    private fun handleResponse(step: StepDefinition, response: HttpResponse): StepResult {
        return if (response.isSuccess()) {
            successResult(step, mapOf(
                "statusCode" to response.statusCode,
                "body" to (response.body ?: "")
            ))
        } else {
            failResult(step, "HTTP ${response.statusCode}: ${response.body ?: "No response body"}")
        }
    }
}
