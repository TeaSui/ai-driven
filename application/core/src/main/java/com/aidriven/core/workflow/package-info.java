/**
 * Flexible, modular workflow automation framework.
 *
 * <p>Core abstractions:
 * <ul>
 *   <li>{@link com.aidriven.core.workflow.WorkflowStep} — a single executable unit of work</li>
 *   <li>{@link com.aidriven.core.workflow.WorkflowDefinition} — an ordered sequence of step IDs</li>
 *   <li>{@link com.aidriven.core.workflow.WorkflowContext} — mutable property bag passed between steps</li>
 *   <li>{@link com.aidriven.core.workflow.WorkflowStepRegistry} — resolves steps by ID</li>
 *   <li>{@link com.aidriven.core.workflow.WorkflowDefinitionRegistry} — resolves definitions by ID</li>
 *   <li>{@link com.aidriven.core.workflow.WorkflowEngine} — orchestrates execution</li>
 * </ul>
 *
 * <p>Adding a new workflow:
 * <ol>
 *   <li>Implement {@link com.aidriven.core.workflow.WorkflowStep} for each new step</li>
 *   <li>Register steps in {@link com.aidriven.core.workflow.WorkflowStepRegistry}</li>
 *   <li>Create a {@link com.aidriven.core.workflow.SimpleWorkflowDefinition} with the step IDs</li>
 *   <li>Register the definition in {@link com.aidriven.core.workflow.WorkflowDefinitionRegistry}</li>
 * </ol>
 */
package com.aidriven.core.workflow;
