package com.workflow.plugin.email

/**
 * Abstraction for email sending.
 * Implementations can use SMTP, SendGrid, SES, etc.
 */
interface EmailSender {
    fun send(message: EmailMessage): EmailResult
    fun sendTemplate(to: String, templateId: String, templateData: Map<String, Any>): EmailResult
}

data class EmailMessage(
    val to: List<String>,
    val from: String,
    val subject: String,
    val textBody: String,
    val htmlBody: String? = null,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val replyTo: String? = null,
    val headers: Map<String, String> = emptyMap()
)

data class EmailResult(
    val success: Boolean,
    val messageId: String? = null,
    val error: String? = null
) {
    companion object {
        fun success(messageId: String) = EmailResult(success = true, messageId = messageId)
        fun failure(error: String) = EmailResult(success = false, error = error)
    }
}

/**
 * No-op email sender for testing and development.
 */
class NoOpEmailSender : EmailSender {
    private val log = org.slf4j.LoggerFactory.getLogger(NoOpEmailSender::class.java)

    override fun send(message: EmailMessage): EmailResult {
        log.info("[NO-OP] Sending email to={} subject='{}'", message.to, message.subject)
        return EmailResult.success("noop-${System.currentTimeMillis()}")
    }

    override fun sendTemplate(to: String, templateId: String, templateData: Map<String, Any>): EmailResult {
        log.info("[NO-OP] Sending template email to={} templateId='{}'", to, templateId)
        return EmailResult.success("noop-template-${System.currentTimeMillis()}")
    }
}
