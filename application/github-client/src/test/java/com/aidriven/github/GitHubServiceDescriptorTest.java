package com.aidriven.github;

import com.aidriven.spi.ServiceCategory;
import com.aidriven.spi.ServiceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class GitHubServiceDescriptorTest {

    private final GitHubServiceDescriptor descriptor = new GitHubServiceDescriptor();

    @Test
    void should_have_correct_id() {
        assertEquals("github", descriptor.id());
    }

    @Test
    void should_have_correct_category() {
        assertEquals(ServiceCategory.SOURCE_CONTROL, descriptor.category());
    }

    @Test
    void should_require_owner_repo_token() {
        assertTrue(descriptor.requiredConfigKeys().contains("github.owner"));
        assertTrue(descriptor.requiredConfigKeys().contains("github.repo"));
        assertTrue(descriptor.requiredConfigKeys().contains("github.token"));
    }

    @Test
    void should_have_optional_defaults() {
        assertEquals("https://api.github.com", descriptor.optionalConfigDefaults().get("github.apiBase"));
    }

    @Test
    void should_be_discoverable_via_service_loader() {
        ServiceLoader<ServiceDescriptor> loader = ServiceLoader.load(ServiceDescriptor.class);
        boolean found = false;
        for (ServiceDescriptor sd : loader) {
            if ("github".equals(sd.id())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "GitHubServiceDescriptor should be discoverable via ServiceLoader");
    }
}
