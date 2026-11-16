package dev.universalai.agent.data.remote

/** Error envelope returned by the backend: {"error":{"code","message","details"}}. */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val details: String? = null,
) : Exception(message) {

    val isAuthError: Boolean get() = status == 401
    val isRetryable: Boolean get() = status == 0 || status == 429 || status >= 500

    companion object {
        fun network(cause: Throwable): ApiException = ApiException(
            status = 0,
            code = "network_unavailable",
            message = "Can't reach the server. Check the server address and your connection.",
            details = cause.message,
        )

        fun notConfigured(): ApiException = ApiException(
            status = 0,
            code = "not_configured",
            message = "Set the API server address in Settings first.",
        )
    }
}

data class SseEvent(val event: String?, val data: String)
