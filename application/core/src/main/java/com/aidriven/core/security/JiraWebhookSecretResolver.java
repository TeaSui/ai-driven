package com.aidriven.core.security;

import com.aidriven.core.service.SecretsService;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Resolves the Jira webhook pre-shared token using a three-tier precedence
 * strategy.
 *
 * <ol>
 * <li>Raw environment variable {@code JIRA_WEBHOOK_SECRET} (local dev /
 * backward-compat)</li>
 * <li>AWS Secrets Manager ARN from {@code JIRA_WEBHOOK_SECRET_ARN}
 * (production)</li>
 * <li>{@code null} -- verification will be skipped with a WARN log</li>
 * </ol>
 *
 * <p>
 * The resolved value is cached after the first call so that warm invocations
 * never pay an extra Secrets Manager round-trip.
 */
@Slf4j
public class JiraWebhookSecretResolver {

    private final Optional<String> webhookSecret;
    private final Optional<String> webhookSecretArn;
    private final SecretsService secretsService;

    /**
     * Cached after first resolution; {@code volatile} ensures cross-thread
     * visibility.
     */
    private volatile String cachedToken;

    /**
     * Constructs a resolver with explicit secret values (no AppConfig dependency).
     *
     * @param webhookSecret    raw secret value (tier 1), may be null/empty
     * @param webhookSecretArn Secrets Manager ARN (tier 2), may be null/empty
     * @param secretsService   service to fetch from Secrets Manager
     */
    public JiraWebhookSecretResolver(String webhookSecret, String webhookSecretArn,
                                     SecretsService secretsService) {
        this.webhookSecret = Optional.ofNullable(webhookSecret).filter(s -> !s.isBlank());
        this.webhookSecretArn = Optional.ofNullable(webhookSecretArn).filter(s -> !s.isBlank());
        this.secretsService = secretsService;
    }

    /**
     * Returns the Jira webhook token, resolving and caching it on first call.
     *
     * @return the token string, or {@code null} if not configured (verification
     *         will be skipped)
     */
    public String resolve() {
        if (cachedToken != null) {
            return cachedToken;
        }

        // Tier 1: raw env var (local dev / backward-compat)
        webhookSecret.ifPresent(v -> cachedToken = v);
        if (cachedToken != null) {
            return cachedToken;
        }

        // Tier 2: Secrets Manager ARN (production deployment)
        webhookSecretArn.ifPresent(arn -> {
            log.debug("Resolving Jira webhook secret from Secrets Manager ARN");
            cachedToken = secretsService.getSecret(arn);
        });

        if (cachedToken == null) {
            log.debug("Jira webhook secret not configured (JIRA_WEBHOOK_SECRET / JIRA_WEBHOOK_SECRET_ARN)");
        }

        return cachedToken;
    }
}
