package dev.universalai.agent.core

import dev.universalai.agent.data.remote.ApiException
import kotlinx.coroutines.CancellationException

/** Run a suspending API call and convert failures into [AppResult.Failure]. */
suspend fun <T> safeCall(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (api: ApiException) {
    AppResult.Failure(api.message, api.code, api.isRetryable)
} catch (error: Throwable) {
    AppResult.Failure(
        message = error.message ?: "Something went wrong.",
        code = "unexpected",
        retryable = false,
    )
}
