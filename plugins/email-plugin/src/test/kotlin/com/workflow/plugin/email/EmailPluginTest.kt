package com.workflow.plugin.email

import com.workflow.api.*
import com.workflow.tenant.TenantConfiguration
import com.workflow.tenant.TenantPlan
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EmailPluginTest {

    private lateinit var emailSender: EmailSender
    private lateinit var plugin: EmailPlugin
    private lateinit var tenantConfig: TenantConfiguration

    @BeforeEach
    fun setUp() {
        emailSender = mockk()
        plugin = EmailPlugin(emailSender)
        tenantConfig = TenantConfiguration(
            tenantId = TenantId.of("test-tenant"),
            name = "Test Tenant",
            plan = TenantPlan.STARTER,
            enabledPlugins = setOf("email-plugin")
        )
    }

    @Test
    fun `should send email successfully`() {
        every { emailSender.send(any()) } returns EmailResult.success("msg-123")

        val step = StepDefinition(
            id = StepId.generate(),
            name = "Send Welcome Email",
            pluginId = "email-plugin",
            actionId = "send",
            config = mapOf(
                "to" to "user@example.com",
                "subject" to "Welcome!",
                "body" to "Hello {{name}}, welcome!"
            )
        )
        val context = ExecutionContext(
            triggerData = mapOf("name" to "Alice")
        )

        val result = plugin.execute("send", step, context, tenantConfig)

        assertThat(result.status).isEqualTo(StepStatus.SUCCESS)
        assertThat(result.output["messageId"]).isEqualTo("msg-123")
        verify { emailSender.send(match { it.to == listOf("user@example.com") }) }
    }

    @Test
    fun `should resolve template variables in email body`() {
        every { emailSender.send(any()) } returns EmailResult.success("msg-456")

        val step = StepDefinition(
            id = StepId.generate(),
            name = "Send Email",
            pluginId = "email-plugin",
            actionId = "send",
            config = mapOf(
                "to" to "{{email}}",
                "subject" to "Hello {{name}}",
                "body" to "Dear {{name}}, your order {{orderId}} is ready."
            )
        )
        val context = ExecutionContext(
            triggerData = mapOf(
                "email" to "bob@example.com",
                "name" to "Bob",
                "orderId" to "ORD-789"
            )
        )

        plugin.execute("send", step, context, tenantConfig)

        verify {
            emailSender.send(match {
                it.to == listOf("bob@example.com") &&
                    it.subject == "Hello Bob" &&
                    it.textBody == "Dear Bob, your order ORD-789 is ready."
            })
        }
    }

    @Test
    fun `should return failure when email sender fails`() {
        every { emailSender.send(any()) } returns EmailResult.failure("SMTP connection refused")

        val step = StepDefinition(
            id = StepId.generate(),
            name = "Send Email",
            pluginId = "email-plugin",
            actionId = "send",
            config = mapOf(
                "to" to "user@example.com",
                "subject" to "Test",
                "body" to "Test body"
            )
        )

        val result = plugin.execute("send", step, ExecutionContext(), tenantConfig)

        assertThat(result.status).isEqualTo(StepStatus.FAILED)
        assertThat(result.error).contains("SMTP connection refused")
    }

    @Test
    fun `should validate required fields`() {
        val step = StepDefinition(
            id = StepId.generate(),
            name = "Incomplete Email",
            pluginId = "email-plugin",
            actionId = "send",
            config = mapOf("to" to "user@example.com") // missing subject and body
        )

        val validation = plugin.validate(step, tenantConfig)

        assertThat(validation.valid).isFalse()
        assertThat(validation.errors).hasSize(2)
    }

    @Test
    fun `plugin should have correct metadata`() {
        assertThat(plugin.pluginId).isEqualTo("email-plugin")
        assertThat(plugin.supportedActions).hasSize(2)
        assertThat(plugin.supportsAction("send")).isTrue()
        assertThat(plugin.supportsAction("unknown")).isFalse()
    }
}
