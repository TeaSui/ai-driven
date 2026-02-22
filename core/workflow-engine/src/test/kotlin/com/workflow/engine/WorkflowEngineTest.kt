package com.workflow.engine

import com.workflow.api.*
import com.workflow.plugin.DefaultPluginRegistry
import com.workflow.plugin.NoOpTestPlugin
import com.workflow.tenant.InMemoryTenantRegistry
import com.workflow.tenant.TenantConfiguration
import com.workflow.tenant.TenantPlan
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WorkflowEngineTest {

    private lateinit var engine: WorkflowEngine
    private lateinit var executionRepo: InMemoryWorkflowExecutionRepository
    private lateinit var eventPublisher: InMemoryWorkflowEventPublisher
    private lateinit var pluginRegistry: DefaultPluginRegistry
    private lateinit var tenantRegistry: InMemoryTenantRegistry

    private val tenantId = TenantId.of("test-tenant")

    @BeforeEach
    fun setUp() {
        executionRepo = InMemoryWorkflowExecutionRepository()
        eventPublisher = InMemoryWorkflowEventPublisher()
        pluginRegistry = DefaultPluginRegistry()
        tenantRegistry = InMemoryTenantRegistry()

        // Register a no-op test plugin
        pluginRegistry.register(NoOpTestPlugin())

        // Register tenant
        tenantRegistry.register(
            TenantConfiguration(
                tenantId = tenantId,
                name = "Test Tenant",
                plan = TenantPlan.PROFESSIONAL,
                enabledPlugins = setOf("noop-plugin")
            )
        )

        engine = WorkflowEngine(
            pluginRegistry = pluginRegistry,
            tenantRegistry = tenantRegistry,
            executionRepository = executionRepo,
            eventPublisher = eventPublisher
        )
    }

    @Test
    fun `should trigger workflow and create execution`() {
        val workflow = buildWorkflow()
        val executionId = engine.trigger(workflow, mapOf("key" to "value"))

        val execution = executionRepo.findById(executionId)
        assertThat(execution).isNotNull
        assertThat(execution!!.status).isEqualTo(ExecutionStatus.PENDING)
        assertThat(eventPublisher.eventsOfType<WorkflowTriggered>()).hasSize(1)
    }

    @Test
    fun `should execute workflow and complete successfully`() {
        val workflow = buildWorkflow()
        val executionId = engine.trigger(workflow)
        val result = engine.execute(workflow, executionId)

        assertThat(result.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(result.stepResults).hasSize(1)
        assertThat(result.stepResults[0].status).isEqualTo(StepStatus.SUCCESS)
        assertThat(eventPublisher.eventsOfType<WorkflowCompleted>()).hasSize(1)
    }

    @Test
    fun `should skip step when conditions not met`() {
        val step = StepDefinition(
            id = StepId.generate(),
            name = "Conditional Step",
            pluginId = "noop-plugin",
            actionId = "noop",
            conditions = listOf(
                ConditionDefinition(
                    field = "status",
                    operator = ConditionOperator.EQUALS,
                    value = "active"
                )
            )
        )
        val workflow = WorkflowDefinition(
            id = WorkflowId.generate(),
            tenantId = tenantId,
            name = "Conditional Workflow",
            trigger = WebhookTrigger(path = "/test"),
            steps = listOf(step)
        )

        val executionId = engine.trigger(workflow, mapOf("status" to "inactive"))
        val result = engine.execute(workflow, executionId)

        assertThat(result.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(result.stepResults[0].status).isEqualTo(StepStatus.SKIPPED)
    }

    @Test
    fun `should publish events throughout execution lifecycle`() {
        val workflow = buildWorkflow()
        val executionId = engine.trigger(workflow)
        engine.execute(workflow, executionId)

        assertThat(eventPublisher.events).hasSize(4) // Triggered, Started, StepExecuted, Completed
        assertThat(eventPublisher.eventsOfType<WorkflowTriggered>()).hasSize(1)
        assertThat(eventPublisher.eventsOfType<WorkflowStarted>()).hasSize(1)
        assertThat(eventPublisher.eventsOfType<StepExecuted>()).hasSize(1)
        assertThat(eventPublisher.eventsOfType<WorkflowCompleted>()).hasSize(1)
    }

    private fun buildWorkflow(): WorkflowDefinition {
        return WorkflowDefinition(
            id = WorkflowId.generate(),
            tenantId = tenantId,
            name = "Test Workflow",
            trigger = WebhookTrigger(path = "/test"),
            steps = listOf(
                StepDefinition(
                    id = StepId.generate(),
                    name = "No-op Step",
                    pluginId = "noop-plugin",
                    actionId = "noop"
                )
            )
        )
    }
}
