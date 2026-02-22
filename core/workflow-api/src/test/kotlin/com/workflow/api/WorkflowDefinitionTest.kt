package com.workflow.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WorkflowDefinitionTest {

    @Test
    fun `should create valid workflow definition`() {
        val workflow = WorkflowDefinition(
            id = WorkflowId.generate(),
            tenantId = TenantId.of("tenant-1"),
            name = "Test Workflow",
            trigger = WebhookTrigger(path = "/webhook/test"),
            steps = listOf(
                StepDefinition(
                    id = StepId.generate(),
                    name = "Send Email",
                    pluginId = "email-plugin",
                    actionId = "send"
                )
            )
        )

        assertThat(workflow.name).isEqualTo("Test Workflow")
        assertThat(workflow.steps).hasSize(1)
        assertThat(workflow.enabled).isTrue()
    }

    @Test
    fun `should reject blank workflow name`() {
        assertThatThrownBy {
            WorkflowDefinition(
                id = WorkflowId.generate(),
                tenantId = TenantId.of("tenant-1"),
                name = "",
                trigger = WebhookTrigger(path = "/webhook/test"),
                steps = listOf(
                    StepDefinition(
                        id = StepId.generate(),
                        name = "Step",
                        pluginId = "plugin",
                        actionId = "action"
                    )
                )
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("name must not be blank")
    }

    @Test
    fun `should reject workflow with no steps`() {
        assertThatThrownBy {
            WorkflowDefinition(
                id = WorkflowId.generate(),
                tenantId = TenantId.of("tenant-1"),
                name = "Empty Workflow",
                trigger = WebhookTrigger(path = "/webhook/test"),
                steps = emptyList()
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least one step")
    }

    @Test
    fun `execution context should merge data correctly`() {
        val context = ExecutionContext(
            triggerData = mapOf("email" to "user@example.com")
        )
        context.set("name", "John")

        assertThat(context.get("email")).isEqualTo("user@example.com")
        assertThat(context.get("name")).isEqualTo("John")
        assertThat(context.get("missing")).isNull()
    }
}
