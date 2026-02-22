package com.workflow.plugin.webhook

/**
 * Abstraction for HTTP client operations.
 */
interface HttpClient {
    fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Long): HttpResponse
    fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResponse
}

data class HttpResponse(
    val statusCode: Int,
    val body: String?,
    val headers: Map<String, String> = emptyMap()
) {
    fun isSuccess(): Boolean = statusCode in 200..299
}

/**
 * No-op HTTP client for testing.
 */
class NoOpHttpClient : HttpClient {
    private val log = org.slf4j.LoggerFactory.getLogger(NoOpHttpClient::class.java)

    override fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Long): HttpResponse {
        log.info("[NO-OP] POST {} body={}", url, body)
        return HttpResponse(statusCode = 200, body = "{\"status\":\"ok\"}")
    }

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResponse {
        log.info("[NO-OP] GET {}", url)
        return HttpResponse(statusCode = 200, body = "{\"status\":\"ok\"}")
    }
}
