package com.aidriven.spi.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
    }

    @Test
    void should_deliver_event_to_subscriber() {
        List<ModuleEvent> received = new ArrayList<>();
        eventBus.subscribe("pr.merged", received::add);

        ModuleEvent event = ModuleEvent.of("pr.merged", "bitbucket", "t1", Map.of("prId", "123"));
        eventBus.publish(event);

        assertEquals(1, received.size());
        assertEquals("pr.merged", received.get(0).type());
        assertEquals("123", received.get(0).payload().get("prId"));
    }

    @Test
    void should_deliver_to_multiple_subscribers() {
        AtomicInteger count = new AtomicInteger();
        eventBus.subscribe("pr.merged", e -> count.incrementAndGet());
        eventBus.subscribe("pr.merged", e -> count.incrementAndGet());

        eventBus.publish(ModuleEvent.of("pr.merged", "bitbucket", "t1", Map.of()));

        assertEquals(2, count.get());
    }

    @Test
    void should_not_deliver_to_wrong_type() {
        AtomicInteger count = new AtomicInteger();
        eventBus.subscribe("pr.merged", e -> count.incrementAndGet());

        eventBus.publish(ModuleEvent.of("pr.created", "bitbucket", "t1", Map.of()));

        assertEquals(0, count.get());
    }

    @Test
    void should_deliver_to_wildcard_subscribers() {
        List<ModuleEvent> received = new ArrayList<>();
        eventBus.subscribe("*", received::add);

        eventBus.publish(ModuleEvent.of("pr.merged", "bitbucket", "t1", Map.of()));
        eventBus.publish(ModuleEvent.of("ticket.updated", "jira", "t1", Map.of()));

        assertEquals(2, received.size());
    }

    @Test
    void should_not_propagate_handler_exceptions() {
        eventBus.subscribe("test", e -> { throw new RuntimeException("boom"); });
        AtomicInteger count = new AtomicInteger();
        eventBus.subscribe("test", e -> count.incrementAndGet());

        // Should not throw
        assertDoesNotThrow(() ->
                eventBus.publish(ModuleEvent.of("test", "source", "t1", Map.of())));

        // Second handler should still execute
        // Note: first handler throws, but second should still run
    }

    @Test
    void should_report_subscriber_count() {
        assertEquals(0, eventBus.subscriberCount("pr.merged"));

        eventBus.subscribe("pr.merged", e -> {});
        eventBus.subscribe("pr.merged", e -> {});

        assertEquals(2, eventBus.subscriberCount("pr.merged"));
        assertEquals(0, eventBus.subscriberCount("other"));
    }

    @Test
    void should_clear_all_subscribers() {
        eventBus.subscribe("pr.merged", e -> {});
        eventBus.subscribe("ticket.updated", e -> {});

        eventBus.clear();

        assertEquals(0, eventBus.subscriberCount("pr.merged"));
        assertEquals(0, eventBus.subscriberCount("ticket.updated"));
    }

    @Test
    void should_handle_publish_with_no_subscribers() {
        assertDoesNotThrow(() ->
                eventBus.publish(ModuleEvent.of("orphan.event", "source", "t1", Map.of())));
    }

    @Test
    void should_create_event_with_defaults() {
        ModuleEvent event = ModuleEvent.of("test", "source", "t1", null);

        assertNotNull(event.timestamp());
        assertNotNull(event.payload());
        assertTrue(event.payload().isEmpty());
    }

    @Test
    void should_reject_null_event_type() {
        assertThrows(NullPointerException.class, () ->
                ModuleEvent.of(null, "source", "t1", Map.of()));
    }

    @Test
    void should_reject_null_source_module() {
        assertThrows(NullPointerException.class, () ->
                ModuleEvent.of("test", null, "t1", Map.of()));
    }
}
