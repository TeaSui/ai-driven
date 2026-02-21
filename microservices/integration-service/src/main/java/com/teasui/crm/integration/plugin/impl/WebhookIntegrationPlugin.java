package com.teasui.crm.integration.plugin.impl;

import com.teasui.crm.integration.plugin.IntegrationPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic webhook integration plugin for HTTP callbacks.
 */
@Slf4j
@Component
public class WebhookIntegrationPlugin implements IntegrationPlugin {

    private final WebClient webClient;

    public WebhookIntegrationPlugin(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @Override
    public String getPluginType() {
        return "WEBHOOK";
    }

    @Override
    public String getDisplayName() {
        return "Generic Webhook";
    }

    @Override
    public String getDescription() {
        return "Send HTTP POST requests to any webhook endpoint";
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        if (!config.containsKey("url")) {
            throw new IllegalArgumentException("Webhook config must include 'url'");
        }
    }

    @Override
    public boolean testConnection(Map<String, Object> config, Map<String, Object> credentials) {
        try {
            String url = (String) config.get("url");
            webClient.get().uri(url).retrieve().toBodilessEntity().block();
            return true;
        } catch (Exception e) {
            log.warn("Webhook connection test failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> config, Map<String, Object> credentials, Map<String, Object> payload) {
        String url = (String) config.get("url");
        String authHeader = (String) credentials.getOrDefault("authHeader", null);

        try {
            WebClient.RequestBodySpec request = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON);

            if (authHeader != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, authHeader);
            }

            request.bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Webhook executed successfully to '{}'", url);
            return Map.of("success", true, "url", url);
        } catch (Exception e) {
            log.error("Webhook execution failed: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }
}
