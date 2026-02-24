package com.aidriven.lambda.security;

import com.aidriven.core.config.AppConfig;
import com.aidriven.core.service.SecretsService;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the Jira webhook pre-shared token using a three-tier precedence
 * strategy.
 *
 * <ol>
 * <li>Raw environment variable {@code JIRA_WEBHOOK_SECRET} (local dev /
 * backward-compat)</li>
 * <li>AWS Secrets Manager ARN from {@code JIRA_WEBHOOK_SECRET_ARN}
 * (production)</li>
 * <li>{@code null} — verification will be skipped with a WARN log</li>
 * </ol>
 *
 * <p>
 * The resolved value is cached after the first call so that warm Lambda
 * invocations
 * never pay an extra Secrets Manager round-trip.
 *
 * <p>
 * This class is extracted from {@code JiraWebhookHandler} and
 * {@code AgentWebhookHandler}
 * to eliminate the duplicated {@code volatile cachedJiraWebhookToken} pattern
 * (SRP / DRY).
 */
@Slf4j
public class JiraWebhookSecretResolver {

    private final AppConfig appConfig;
    private final SecretsService secretsService;

    /**
     * Cached after first resolution; {@code volatile} ensures cross-thread
     * visibility.
     */
    private volatile String cachedToken;

    public JiraWebhookSecretResolver(AppConfig appConfig, SecretsService secretsService) {
        this.appConfig = appConfig;
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
        appConfig.getJiraWebhookSecret().ifPresent(v -> cachedToken = v);
        if (cachedToken != null) {
            return cachedToken;
        }

        // Tier 2: Secrets Manager ARN (production deployment)
        appConfig.getJiraWebhookSecretArn().ifPresent(arn -> {
            log.debug("Resolving Jira webhook secret from Secrets Manager ARN");
            cachedToken = secretsService.getSecret(arn);
        });

        if (cachedToken == null) {
            log.debug("Jira webhook secret not configured (JIRA_WEBHOOK_SECRET / JIRA_WEBHOOK_SECRET_ARN)");
        }

        return cachedToken;
    }
}
