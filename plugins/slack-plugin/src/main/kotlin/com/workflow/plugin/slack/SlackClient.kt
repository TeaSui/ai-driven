package com.workflow.plugin.slack

/**
 * Abstraction for Slack API operations.
 */
interface SlackClient {
    fun sendMessage(token: String, channel: String, text: String): SlackResult
    fun sendAttachment(
        token: String,
        channel: String,
        title: String,
        text: String,
        color: String
    ): SlackResult
}

data class SlackResult(
    val ok: Boolean,
    val ts: String? = null,
    val error: String? = null
)

/**
 * No-op Slack client for testing and development.
 */
class NoOpSlackClient : SlackClient {
    private val log = org.slf4j.LoggerFactory.getLogger(NoOpSlackClient::class.java)

    override fun sendMessage(token: String, channel: String, text: String): SlackResult {
        log.info("[NO-OP] Slack message to #{}: {}", channel, text)
        return SlackResult(ok = true, ts = System.currentTimeMillis().toString())
    }

    override fun sendAttachment(
        token: String,
        channel: String,
        title: String,
        text: String,
        color: String
    ): SlackResult {
        log.info("[NO-OP] Slack attachment to #{}: title='{}' text='{}'", channel, title, text)
        return SlackResult(ok = true, ts = System.currentTimeMillis().toString())
    }
}
