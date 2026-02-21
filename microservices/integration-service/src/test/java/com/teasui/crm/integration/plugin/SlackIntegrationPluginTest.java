package com.teasui.crm.integration.plugin;

import com.teasui.crm.integration.plugin.impl.SlackIntegrationPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SlackIntegrationPlugin Tests")
class SlackIntegrationPluginTest {

    private SlackIntegrationPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new SlackIntegrationPlugin(WebClient.builder());
    }

    @Test
    @DisplayName("Should return correct plugin type")
    void shouldReturnCorrectPluginType() {
        assertThat(plugin.getPluginType()).isEqualTo("SLACK");
    }

    @Test
    @DisplayName("Should return display name")
    void shouldReturnDisplayName() {
        assertThat(plugin.getDisplayName()).isEqualTo("Slack");
    }

    @Test
    @DisplayName("Should validate config with channel")
    void shouldValidateConfigWithChannel() {
        assertThatNoException().isThrownBy(() ->
                plugin.validateConfig(Map.of("channel", "#general")));
    }

    @Test
    @DisplayName("Should throw when config missing channel")
    void shouldThrowWhenConfigMissingChannel() {
        assertThatThrownBy(() -> plugin.validateConfig(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }
}
