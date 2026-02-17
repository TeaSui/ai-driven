package com.aidriven.registry;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @Test
    void defaultContext_hasDefaultTenantId() {
        TenantContext ctx = TenantContext.defaultContext();

        assertEquals("default", ctx.tenantId());
        assertTrue(ctx.isDefault());
        assertEquals("Default Tenant", ctx.displayName());
    }

    @Test
    void getSecretArn_returnsValueWhenPresent() {
        TenantContext ctx = new TenantContext(
                "tenant-1", "Acme Corp",
                Map.of("claude", "arn:aws:secretsmanager:us-east-1:123:secret:claude"),
                Map.of(), Map.of());

        Optional<String> arn = ctx.getSecretArn("claude");
        assertTrue(arn.isPresent());
        assertEquals("arn:aws:secretsmanager:us-east-1:123:secret:claude", arn.get());
    }

    @Test
    void getSecretArn_returnsEmptyWhenMissing() {
        TenantContext ctx = TenantContext.defaultContext();

        assertTrue(ctx.getSecretArn("nonexistent").isEmpty());
    }

    @Test
    void getConfig_returnsValueWhenPresent() {
        TenantContext ctx = new TenantContext(
                "t1", "Test", Map.of(),
                Map.of("maxTurns", "15"),
                Map.of());

        assertEquals("15", ctx.getConfig("maxTurns", "10"));
    }

    @Test
    void getConfig_returnsDefaultWhenMissing() {
        TenantContext ctx = TenantContext.defaultContext();

        assertEquals("10", ctx.getConfig("maxTurns", "10"));
    }

    @Test
    void isFeatureEnabled_returnsTrueWhenEnabled() {
        TenantContext ctx = new TenantContext(
                "t1", "Test", Map.of(), Map.of(),
                Map.of("agent-mode", true, "mcp-bridge", false));

        assertTrue(ctx.isFeatureEnabled("agent-mode"));
        assertFalse(ctx.isFeatureEnabled("mcp-bridge"));
    }

    @Test
    void isFeatureEnabled_returnsFalseWhenMissing() {
        TenantContext ctx = TenantContext.defaultContext();

        assertFalse(ctx.isFeatureEnabled("unknown-feature"));
    }

    @Test
    void isDefault_returnsFalseForCustomTenant() {
        TenantContext ctx = new TenantContext(
                "acme-corp", "Acme", Map.of(), Map.of(), Map.of());

        assertFalse(ctx.isDefault());
    }

    @Test
    void customTenant_hasCorrectFields() {
        Map<String, String> secrets = Map.of(
                "claude", "arn:claude",
                "jira", "arn:jira");
        Map<String, String> config = Map.of(
                "platform", "GITHUB",
                "maxTurns", "20");
        Map<String, Boolean> features = Map.of(
                "agent-mode", true,
                "guardrails", true);

        TenantContext ctx = new TenantContext(
                "acme", "Acme Corp", secrets, config, features);

        assertEquals("acme", ctx.tenantId());
        assertEquals("Acme Corp", ctx.displayName());
        assertEquals(2, ctx.secretArns().size());
        assertEquals("GITHUB", ctx.getConfig("platform", "BITBUCKET"));
        assertTrue(ctx.isFeatureEnabled("guardrails"));
    }
}
