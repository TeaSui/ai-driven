package com.aidriven.bitbucket;

import com.aidriven.spi.ServiceCategory;
import com.aidriven.spi.ServiceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class BitbucketServiceDescriptorTest {

    private final BitbucketServiceDescriptor descriptor = new BitbucketServiceDescriptor();

    @Test
    void should_have_correct_id() {
        assertEquals("bitbucket", descriptor.id());
    }

    @Test
    void should_have_correct_category() {
        assertEquals(ServiceCategory.SOURCE_CONTROL, descriptor.category());
    }

    @Test
    void should_require_workspace_and_credentials() {
        assertTrue(descriptor.requiredConfigKeys().contains("bitbucket.workspace"));
        assertTrue(descriptor.requiredConfigKeys().contains("bitbucket.repoSlug"));
        assertTrue(descriptor.requiredConfigKeys().contains("bitbucket.username"));
        assertTrue(descriptor.requiredConfigKeys().contains("bitbucket.appPassword"));
    }

    @Test
    void should_have_optional_defaults() {
        assertFalse(descriptor.optionalConfigDefaults().isEmpty());
        assertEquals("https://api.bitbucket.org/2.0", descriptor.optionalConfigDefaults().get("bitbucket.apiBase"));
    }

    @Test
    void should_have_version() {
        assertNotNull(descriptor.version());
        assertFalse(descriptor.version().isBlank());
    }

    @Test
    void should_have_display_name() {
        assertNotNull(descriptor.displayName());
        assertTrue(descriptor.displayName().contains("Bitbucket"));
    }

    @Test
    void should_be_discoverable_via_service_loader() {
        ServiceLoader<ServiceDescriptor> loader = ServiceLoader.load(ServiceDescriptor.class);
        boolean found = false;
        for (ServiceDescriptor sd : loader) {
            if ("bitbucket".equals(sd.id())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "BitbucketServiceDescriptor should be discoverable via ServiceLoader");
    }
}
