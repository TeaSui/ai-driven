package com.workflow.app.config

import com.workflow.plugin.PluginRegistry
import com.workflow.plugin.email.EmailPlugin
import com.workflow.plugin.email.NoOpEmailSender
import com.workflow.plugin.slack.NoOpSlackClient
import com.workflow.plugin.slack.SlackPlugin
import com.workflow.plugin.webhook.NoOpHttpClient
import com.workflow.plugin.webhook.WebhookPlugin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PluginConfig {

    @Bean
    fun emailPlugin(): EmailPlugin = EmailPlugin(NoOpEmailSender())

    @Bean
    fun webhookPlugin(): WebhookPlugin = WebhookPlugin(NoOpHttpClient())

    @Bean
    fun slackPlugin(): SlackPlugin = SlackPlugin(NoOpSlackClient())

    /**
     * Register all plugins with the registry at startup.
     * New plugins are automatically picked up when added as Spring beans.
     */
    @Bean
    fun pluginRegistrar(
        registry: PluginRegistry,
        emailPlugin: EmailPlugin,
        webhookPlugin: WebhookPlugin,
        slackPlugin: SlackPlugin
    ): PluginRegistrar {
        return PluginRegistrar(registry, listOf(emailPlugin, webhookPlugin, slackPlugin))
    }
}

class PluginRegistrar(
    private val registry: PluginRegistry,
    private val plugins: List<com.workflow.plugin.WorkflowPlugin>
) {
    init {
        plugins.forEach { registry.register(it) }
    }
}
