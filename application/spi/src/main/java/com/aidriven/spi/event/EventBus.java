package com.aidriven.spi.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Simple in-process event bus for inter-module communication.
 * Modules can publish events and subscribe to event types without
 * direct dependencies on each other.
 *
 * <p>For distributed deployments, this can be backed by SQS/SNS
 * instead of in-memory dispatch.</p>
 */
public class EventBus {

    private final Map<String, List<Consumer<ModuleEvent>>> subscribers = new ConcurrentHashMap<>();

    /**
     * Subscribe to events of a specific type.
     *
     * @param eventType The event type to listen for (e.g., "pr.merged")
     * @param handler   The handler to invoke when the event occurs
     */
    public void subscribe(String eventType, Consumer<ModuleEvent> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Publish an event to all subscribers of its type.
     *
     * @param event The event to publish
     */
    public void publish(ModuleEvent event) {
        List<Consumer<ModuleEvent>> handlers = subscribers.get(event.type());
        if (handlers != null) {
            for (Consumer<ModuleEvent> handler : handlers) {
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    // Log but don't propagate — one handler failure shouldn't block others
                    System.err.println("Event handler failed for " + event.type() + ": " + e.getMessage());
                }
            }
        }

        // Also notify wildcard subscribers
        List<Consumer<ModuleEvent>> wildcardHandlers = subscribers.get("*");
        if (wildcardHandlers != null) {
            for (Consumer<ModuleEvent> handler : wildcardHandlers) {
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    System.err.println("Wildcard event handler failed: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Returns the number of subscribers for a given event type.
     */
    public int subscriberCount(String eventType) {
        List<Consumer<ModuleEvent>> handlers = subscribers.get(eventType);
        return handlers != null ? handlers.size() : 0;
    }

    /**
     * Removes all subscribers. Useful for testing.
     */
    public void clear() {
        subscribers.clear();
    }
}
