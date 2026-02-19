package com.aidriven.core.spi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void should_return_default_context_when_none_set() {
        TenantContext ctx = TenantContext.current();

        assertNotNull(ctx);
        assertEquals("default", ctx.getTenantId());
    }

    @Test
    void should_set_and_retrieve_tenant_context() {
        TenantContext ctx = TenantContext.of("acme-corp", Map.of(
                "source_control", "github",
                "issue_tracker", "jira"));

        TenantContext.set(ctx);

        TenantContext current = TenantContext.current();
        assertEquals("acme-corp", current.getTenantId());
        assertEquals("github", current.getPreference("source_control").orElse(null));
        assertEquals("jira", current.getPreference("issue_tracker").orElse(null));
    }

    @Test
    void should_return_empty_for_missing_preference() {
        TenantContext ctx = TenantContext.of("tenant-1", Map.of());
        TenantContext.set(ctx);

        assertTrue(TenantContext.current().getPreference("nonexistent").isEmpty());
    }

    @Test
    void should_return_default_value_for_missing_preference() {
        TenantContext ctx = TenantContext.of("tenant-1", Map.of());
        TenantContext.set(ctx);

        assertEquals("bitbucket",
                TenantContext.current().getPreference("source_control", "bitbucket"));
    }

    @Test
    void should_clear_context() {
        TenantContext.set(TenantContext.of("tenant-1", Map.of()));
        TenantContext.clear();

        assertEquals("default", TenantContext.current().getTenantId());
    }

    @Test
    void should_support_secrets() {
        TenantContext ctx = TenantContext.of("tenant-1",
                Map.of("source_control", "github"),
                Map.of("GITHUB_TOKEN", "ghp_secret123"));

        TenantContext.set(ctx);

        assertEquals("ghp_secret123",
                TenantContext.current().getSecret("GITHUB_TOKEN").orElse(null));
        assertTrue(TenantContext.current().getSecret("MISSING").isEmpty());
    }

    @Test
    void should_return_unmodifiable_preferences() {
        TenantContext ctx = TenantContext.of("t1", Map.of("key", "val"));
        TenantContext.set(ctx);

        Map<String, String> prefs = TenantContext.current().getPreferences();
        assertThrows(UnsupportedOperationException.class, () -> prefs.put("new", "val"));
    }

    @Test
    void should_isolate_between_threads() throws Exception {
        TenantContext.set(TenantContext.of("main-thread", Map.of()));

        Thread other = new Thread(() -> {
            // Should get default context, not main-thread's
            assertEquals("default", TenantContext.current().getTenantId());

            TenantContext.set(TenantContext.of("other-thread", Map.of()));
            assertEquals("other-thread", TenantContext.current().getTenantId());
            TenantContext.clear();
        });
        other.start();
        other.join();

        // Main thread should still have its own context
        assertEquals("main-thread", TenantContext.current().getTenantId());
    }

    @Test
    void toString_includes_tenantId() {
        TenantContext ctx = TenantContext.of("acme", Map.of("key", "val"));
        String str = ctx.toString();

        assertTrue(str.contains("acme"));
        assertTrue(str.contains("key"));
    }
}
