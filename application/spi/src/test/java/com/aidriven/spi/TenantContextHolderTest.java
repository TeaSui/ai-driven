package com.aidriven.spi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextHolderTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void should_return_empty_when_not_set() {
        Optional<TenantContext> ctx = TenantContextHolder.get();

        assertTrue(ctx.isEmpty());
    }

    @Test
    void should_return_context_when_set() {
        TenantContext ctx = TenantContext.builder("t1").build();
        TenantContextHolder.set(ctx);

        Optional<TenantContext> result = TenantContextHolder.get();

        assertTrue(result.isPresent());
        assertEquals("t1", result.get().getTenantId());
    }

    @Test
    void should_clear_context() {
        TenantContextHolder.set(TenantContext.builder("t1").build());
        TenantContextHolder.clear();

        assertTrue(TenantContextHolder.get().isEmpty());
    }

    @Test
    void require_should_throw_when_not_set() {
        assertThrows(IllegalStateException.class,
                TenantContextHolder::require);
    }

    @Test
    void require_should_return_context_when_set() {
        TenantContext ctx = TenantContext.builder("t1").build();
        TenantContextHolder.set(ctx);

        TenantContext result = TenantContextHolder.require();

        assertEquals("t1", result.getTenantId());
    }

    @Test
    void should_isolate_between_threads() throws Exception {
        TenantContextHolder.set(TenantContext.builder("main-thread").build());

        Thread other = new Thread(() -> {
            assertTrue(TenantContextHolder.get().isEmpty(),
                    "Other thread should not see main thread's context");
        });
        other.start();
        other.join();

        assertEquals("main-thread", TenantContextHolder.require().getTenantId());
    }
}
