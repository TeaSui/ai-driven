package com.workflow.app.config

import com.workflow.engine.*
import com.workflow.plugin.DefaultPluginRegistry
import com.workflow.plugin.PluginRegistry
import com.workflow.tenant.InMemoryTenantRegistry
import com.workflow.tenant.TenantRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class WorkflowEngineConfig {

    @Bean
    @ConditionalOnMissingBean
    fun pluginRegistry(): PluginRegistry = DefaultPluginRegistry()

    @Bean
    @ConditionalOnMissingBean
    fun tenantRegistry(): TenantRegistry = InMemoryTenantRegistry()

    @Bean
    @ConditionalOnMissingBean
    fun workflowExecutionRepository(): WorkflowExecutionRepository =
        InMemoryWorkflowExecutionRepository()

    @Bean
    @ConditionalOnMissingBean
    fun workflowDefinitionRepository(): WorkflowDefinitionRepository =
        InMemoryWorkflowDefinitionRepository()

    @Bean
    fun workflowEngine(
        pluginRegistry: PluginRegistry,
        tenantRegistry: TenantRegistry,
        executionRepository: WorkflowExecutionRepository,
        eventPublisher: WorkflowEventPublisher
    ): WorkflowEngine = WorkflowEngine(
        pluginRegistry = pluginRegistry,
        tenantRegistry = tenantRegistry,
        executionRepository = executionRepository,
        eventPublisher = eventPublisher
    )
}
