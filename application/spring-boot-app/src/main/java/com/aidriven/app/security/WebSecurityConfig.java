package com.aidriven.app.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.function.Supplier;

/**
 * Spring Security configuration for the ai-driven application.
 *
 * <p>Security model:
 * <ul>
 *   <li>CSRF is disabled because all endpoints are API-only (no browser forms)</li>
 *   <li>Actuator and health endpoints are public (for ALB health checks)</li>
 *   <li>Webhook endpoints are public at the Spring Security level, but validated
 *       by {@link HmacWebhookFilter} instances registered before the auth chain</li>
 *   <li>Pipeline endpoints are internal (Step Functions HTTP tasks) and permitted
 *       since they are behind the ALB with no public access</li>
 *   <li>All other endpoints require authentication</li>
 * </ul>
 *
 * <p>Webhook validation happens via dedicated filters rather than Spring Security's
 * auth chain because different endpoints use different validation methods:
 * <ul>
 *   <li>GitHub: HMAC-SHA256 on request body ({@code X-Hub-Signature-256})</li>
 *   <li>Jira: Pre-shared token ({@code X-Jira-Webhook-Token} or Bearer)</li>
 *   <li>Bitbucket: HMAC-SHA256 on request body ({@code X-Hub-Signature})</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            HmacWebhookFilter githubWebhookFilter,
            HmacWebhookFilter jiraWebhookFilter,
            HmacWebhookFilter bitbucketWebhookFilter) throws Exception {

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/webhooks/**").permitAll()
                        .requestMatchers("/pipeline/**").permitAll()
                        .requestMatchers("/api/approvals/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(githubWebhookFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jiraWebhookFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(bitbucketWebhookFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    HmacWebhookFilter githubWebhookFilter(
            @Value("${ai-driven.github.webhook-secret:}") String webhookSecret) {
        Supplier<String> secretProvider = () -> webhookSecret;
        return new HmacWebhookFilter("/webhooks/github/**", "X-Hub-Signature-256", secretProvider);
    }

    @Bean
    HmacWebhookFilter jiraWebhookFilter(
            @Value("${ai-driven.jira.webhook-secret:}") String webhookSecret) {
        Supplier<String> secretProvider = () -> webhookSecret;
        return HmacWebhookFilter.forTokenValidation(
                "/webhooks/jira/**", "X-Jira-Webhook-Token", secretProvider);
    }

    @Bean
    HmacWebhookFilter bitbucketWebhookFilter(
            @Value("${ai-driven.bitbucket.webhook-secret:}") String webhookSecret) {
        Supplier<String> secretProvider = () -> webhookSecret;
        return new HmacWebhookFilter("/webhooks/bitbucket/**", "X-Hub-Signature", secretProvider);
    }
}
