package com.teasui.crm.gateway.filter;

import com.teasui.crm.common.security.JwtTokenUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Gateway filter that validates JWT tokens and enriches requests with user context headers.
 */
@Slf4j
@Component
public class AuthenticationFilter extends AbstractGatewayFilterFactory<AuthenticationFilter.Config> {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_TENANT_ID = "X-Tenant-Id";
    private static final String HEADER_USER_ROLES = "X-User-Roles";

    private final JwtTokenUtil jwtTokenUtil;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/actuator"
    );

    public AuthenticationFilter(
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs) {
        super(Config.class);
        this.jwtTokenUtil = new JwtTokenUtil(jwtSecret, expirationMs);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getURI().getPath();

            if (isPublicPath(path)) {
                return chain.filter(exchange);
            }

            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                log.warn("Missing or invalid Authorization header for path: {}", path);
                return onError(exchange, HttpStatus.UNAUTHORIZED);
            }

            String token = authHeader.substring(BEARER_PREFIX.length());
            if (!jwtTokenUtil.validateToken(token)) {
                log.warn("Invalid JWT token for path: {}", path);
                return onError(exchange, HttpStatus.UNAUTHORIZED);
            }

            String userId = jwtTokenUtil.extractUserId(token);
            String tenantId = jwtTokenUtil.extractTenantId(token);
            List<String> roles = jwtTokenUtil.extractRoles(token);

            ServerHttpRequest enrichedRequest = request.mutate()
                    .header(HEADER_USER_ID, userId)
                    .header(HEADER_TENANT_ID, tenantId)
                    .header(HEADER_USER_ROLES, String.join(",", roles))
                    .build();

            log.debug("Authenticated request - userId: {}, tenantId: {}, path: {}", userId, tenantId, path);
            return chain.filter(exchange.mutate().request(enrichedRequest).build());
        };
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> onError(ServerWebExchange exchange, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        return response.setComplete();
    }

    public static class Config {
        // Configuration properties if needed
    }
}
