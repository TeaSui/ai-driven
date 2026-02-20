package com.aidriven.jira;

import com.aidriven.spi.ServiceCategory;
import com.aidriven.spi.ServiceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class JiraServiceDescriptorTest {

    private final JiraServiceDescriptor descriptor = new JiraServiceDescriptor();

    @Test
    void should_have_correct_id() {
        assertEquals("jira", descriptor.id());
    }

    @Test
    void should_have_correct_category() {
        assertEquals(ServiceCategory.ISSUE_TRACKER, descriptor.category());
    }

    @Test
    void should_require_base_url_email_token() {
        assertTrue(descriptor.requiredConfigKeys().contains("jira.baseUrl"));
        assertTrue(descriptor.requiredConfigKeys().contains("jira.email"));
        assertTrue(descriptor.requiredConfigKeys().contains("jira.apiToken"));
    }

    @Test
    void should_be_discoverable_via_service_loader() {
        ServiceLoader<ServiceDescriptor> loader = ServiceLoader.load(ServiceDescriptor.class);
        boolean found = false;
        for (ServiceDescriptor sd : loader) {
            if ("jira".equals(sd.id())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "JiraServiceDescriptor should be discoverable via ServiceLoader");
    }
}
