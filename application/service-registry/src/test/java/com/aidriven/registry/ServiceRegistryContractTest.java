package com.aidriven.registry;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the ServiceRegistry interface contract.
 * Ensures all expected methods are declared and return the correct types.
 */
class ServiceRegistryContractTest {

    @Test
    void interface_should_declare_all_core_methods() {
        Set<String> methods = Arrays.stream(ServiceRegistry.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        // Core infrastructure
        assertTrue(methods.contains("getSecretsService"));
        assertTrue(methods.contains("getTicketStateRepository"));
        assertTrue(methods.contains("getGenerationMetricsRepository"));
        assertTrue(methods.contains("getContextStorageService"));
        assertTrue(methods.contains("getIdempotencyService"));

        // Domain clients
        assertTrue(methods.contains("getIssueTrackerClient"));
        assertTrue(methods.contains("getSourceControlClient"));
        assertTrue(methods.contains("getAiClient"));

        // Agent infrastructure
        assertTrue(methods.contains("getConversationRepository"));
        assertTrue(methods.contains("getConversationWindowManager"));
        assertTrue(methods.contains("getCostTracker"));
        assertTrue(methods.contains("getApprovalStore"));
        assertTrue(methods.contains("createGuardedToolRegistry"));

        // Context & tools
        assertTrue(methods.contains("createContextService"));
        assertTrue(methods.contains("getMcpToolProviders"));
        assertTrue(methods.contains("getTenantContext"));
    }

    @Test
    void getSourceControlClient_should_have_two_overloads() {
        long overloads = Arrays.stream(ServiceRegistry.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("getSourceControlClient"))
                .count();

        assertEquals(2, overloads, "Should have 2 overloads: (Platform) and (Platform, String, String)");
    }
}
