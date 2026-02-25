/**
 * Flexible, modular workflow automation framework.
 *
 * <p>Core abstractions:
 * <ul>
 *   <li>{@link com.aidriven.core.workflow.WorkflowStep} — a single executable unit of work</li>
 *   <li>{@link com.aidriven.core.workflow.WorkflowDefinition} — an ordered composition of steps</li>
 *   <li>{@link com.aidriven.core.workflow.WorkflowRegistry} — maps workflow IDs to definitions</li>
 *   <li>{@link com.aidriven.core.workflow.WorkflowEngine} — executes definitions with retry and state propagation</li>
 *   <li>{@link com.aidriven.core.workflow.WorkflowState} — mutable state bag passed between steps</li>
 *   <li>{@link com.aidriven.core.workflow.WorkflowExecution} — execution record with step results</li>
 * </ul>
 *
 * <p>Adding a new workflow step:
 * <ol>
 *   <li>Implement {@link com.aidriven.core.workflow.WorkflowStep}</li>
 *   <li>Register it in a {@link com.aidriven.core.workflow.WorkflowDefinition} via the builder</li>
 *   <li>Register the definition in the {@link com.aidriven.core.workflow.WorkflowRegistry}</li>
 * </ol>
 */
package com.aidriven.core.workflow;
