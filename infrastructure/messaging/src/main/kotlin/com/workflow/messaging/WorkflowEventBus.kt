package com.workflow.messaging

import com.workflow.api.WorkflowEvent
import com.workflow.engine.WorkflowEventPublisher
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Spring Application Event-based event bus.
 * Bridges workflow domain events to Spring's event system.
 * Can be replaced with Kafka/SQS for distributed deployments.
 */
@Component
class SpringWorkflowEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher
) : WorkflowEventPublisher {

    private val log = LoggerFactory.getLogger(SpringWorkflowEventPublisher::class.java)

    override fun publish(event: WorkflowEvent) {
        log.debug("Publishing event: {} for tenant: {}",
            event::class.simpleName, event.tenantId.value)
        applicationEventPublisher.publishEvent(event)
    }
}

/**
 * Base class for workflow event listeners.
 * Extend this to react to workflow lifecycle events.
 */
abstract class WorkflowEventListener {
    protected val log = LoggerFactory.getLogger(this::class.java)

    @EventListener
    open fun onWorkflowEvent(event: WorkflowEvent) {
        // Override in subclasses to handle specific events
    }
}
