package com.teasui.crm.integration.plugin.impl;

import com.teasui.crm.integration.plugin.IntegrationPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Slack integration plugin for sending messages to Slack channels.
 */
@Slf4j
@Component
public class SlackIntegrationPlugin implements IntegrationPlugin {

    private final WebClient webClient;

    public SlackIntegrationPlugin(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @Override
    public String getPluginType() {
        return "SLACK";
    }

    @Override
    public String getDisplayName() {
        return "Slack";
    }

    @Override
    public String getDescription() {
        return "Send messages and notifications to Slack channels";
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        if (!config.containsKey("channel")) {
            throw new IllegalArgumentException("Slack config must include 'channel'");
        }
    }

    @Override
    public boolean testConnection(Map<String, Object> config, Map<String, Object> credentials) {
        try {
            String webhookUrl = (String) credentials.get("webhookUrl");
            if (webhookUrl == null || webhookUrl.isBlank()) {
                return false;
            }
            Map<String, String> testPayload = Map.of("text", "CRM Integration test - connection successful!");
            webClient.post()
                    .uri(webhookUrl)
                    .bodyValue(testPayload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (Exception e) {
            log.warn("Slack connection test failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> config, Map<String, Object> credentials, Map<String, Object> payload) {
        String webhookUrl = (String) credentials.get("webhookUrl");
        String channel = (String) config.get("channel");
        String message = (String) payload.getOrDefault("message", "Workflow notification");

        Map<String, Object> slackPayload = new HashMap<>();
        slackPayload.put("channel", channel);
        slackPayload.put("text", message);

        try {
            webClient.post()
                    .uri(webhookUrl)
                    .bodyValue(slackPayload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Slack message sent to channel '{}'", channel);
            return Map.of("success", true, "channel", channel);
        } catch (Exception e) {
            log.error("Failed to send Slack message: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }
}
