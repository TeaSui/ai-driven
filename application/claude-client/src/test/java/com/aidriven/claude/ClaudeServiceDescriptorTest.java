package com.aidriven.claude;

import com.aidriven.spi.ServiceCategory;
import com.aidriven.spi.ServiceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeServiceDescriptorTest {

    private final ClaudeServiceDescriptor descriptor = new ClaudeServiceDescriptor();

    @Test
    void should_have_correct_id() {
        assertEquals("claude", descriptor.id());
    }

    @Test
    void should_have_correct_category() {
        assertEquals(ServiceCategory.AI_PROVIDER, descriptor.category());
    }

    @Test
    void should_require_api_key() {
        assertTrue(descriptor.requiredConfigKeys().contains("claude.apiKey"));
    }

    @Test
    void should_have_model_defaults() {
        assertEquals("claude-opus-4-6", descriptor.optionalConfigDefaults().get("claude.model"));
        assertEquals("32768", descriptor.optionalConfigDefaults().get("claude.maxTokens"));
    }

    @Test
    void should_be_discoverable_via_service_loader() {
        ServiceLoader<ServiceDescriptor> loader = ServiceLoader.load(ServiceDescriptor.class);
        boolean found = false;
        for (ServiceDescriptor sd : loader) {
            if ("claude".equals(sd.id())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "ClaudeServiceDescriptor should be discoverable via ServiceLoader");
    }
}
