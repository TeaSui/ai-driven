package com.teasui.crm.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JwtTokenUtil Tests")
class JwtTokenUtilTest {

    private JwtTokenUtil jwtTokenUtil;
    private static final String SECRET = "test-secret-key-that-is-at-least-256-bits-long-for-hs256-algorithm";
    private static final long EXPIRATION_MS = 86400000L;

    @BeforeEach
    void setUp() {
        jwtTokenUtil = new JwtTokenUtil(SECRET, EXPIRATION_MS);
    }

    @Test
    @DisplayName("Should generate valid token")
    void shouldGenerateValidToken() {
        String token = jwtTokenUtil.generateToken("user-001", "testuser", "tenant-001",
                List.of("ROLE_USER"));

        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("Should validate generated token")
    void shouldValidateGeneratedToken() {
        String token = jwtTokenUtil.generateToken("user-001", "testuser", "tenant-001",
                List.of("ROLE_USER"));

        assertThat(jwtTokenUtil.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("Should extract username from token")
    void shouldExtractUsernameFromToken() {
        String token = jwtTokenUtil.generateToken("user-001", "testuser", "tenant-001",
                List.of("ROLE_USER"));

        assertThat(jwtTokenUtil.extractUsername(token)).isEqualTo("testuser");
    }

    @Test
    @DisplayName("Should extract userId from token")
    void shouldExtractUserIdFromToken() {
        String token = jwtTokenUtil.generateToken("user-001", "testuser", "tenant-001",
                List.of("ROLE_USER"));

        assertThat(jwtTokenUtil.extractUserId(token)).isEqualTo("user-001");
    }

    @Test
    @DisplayName("Should extract tenantId from token")
    void shouldExtractTenantIdFromToken() {
        String token = jwtTokenUtil.generateToken("user-001", "testuser", "tenant-001",
                List.of("ROLE_USER"));

        assertThat(jwtTokenUtil.extractTenantId(token)).isEqualTo("tenant-001");
    }

    @Test
    @DisplayName("Should extract roles from token")
    void shouldExtractRolesFromToken() {
        List<String> roles = List.of("ROLE_USER", "ROLE_ADMIN");
        String token = jwtTokenUtil.generateToken("user-001", "testuser", "tenant-001", roles);

        List<String> extractedRoles = jwtTokenUtil.extractRoles(token);
        assertThat(extractedRoles).containsExactlyInAnyOrderElementsOf(roles);
    }

    @Test
    @DisplayName("Should return false for invalid token")
    void shouldReturnFalseForInvalidToken() {
        assertThat(jwtTokenUtil.validateToken("invalid.token.here")).isFalse();
    }

    @Test
    @DisplayName("Should return false for expired token")
    void shouldReturnFalseForExpiredToken() {
        JwtTokenUtil shortLivedUtil = new JwtTokenUtil(SECRET, -1000L);
        String token = shortLivedUtil.generateToken("user-001", "testuser", "tenant-001",
                List.of("ROLE_USER"));

        assertThat(shortLivedUtil.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("Should return false for null token")
    void shouldReturnFalseForNullToken() {
        assertThat(jwtTokenUtil.validateToken(null)).isFalse();
    }
}
