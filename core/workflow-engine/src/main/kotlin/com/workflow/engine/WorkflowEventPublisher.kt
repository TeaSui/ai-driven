package com.workflow.engine

import com.workflow.api.WorkflowEvent
import org.slf4j.LoggerFactory

/**
 * Publisher for workflow domain events.
 * Decouples the engine from event consumers.
 */
interface WorkflowEventPublisher {
    fun publish(event: WorkflowEvent)
}

/**
 * Composite publisher that delegates to multiple publishers.
 */
class CompositeWorkflowEventPublisher(
    private val publishers: List<WorkflowEventPublisher>
) : WorkflowEventPublisher {
    override fun publish(event: WorkflowEvent) {
        publishers.forEach { it.publish(event) }
    }
}

/**
 * Logging-only event publisher for development.
 */
class LoggingWorkflowEventPublisher : WorkflowEventPublisher {
    private val log = LoggerFactory.getLogger(LoggingWorkflowEventPublisher::class.java)

    override fun publish(event: WorkflowEvent) {
        log.info("WorkflowEvent: type={} tenant={}",
            event::class.simpleName, event.tenantId.value)
    }
}

/**
 * In-memory event publisher that stores events for testing.
 */
class InMemoryWorkflowEventPublisher : WorkflowEventPublisher {
    private val _events = mutableListOf<WorkflowEvent>()
    val events: List<WorkflowEvent> get() = _events.toList()

    override fun publish(event: WorkflowEvent) {
        _events.add(event)
    }

    fun clear() = _events.clear()

    inline fun <reified T : WorkflowEvent> eventsOfType(): List<T> =
        _events.filterIsInstance<T>()
}
