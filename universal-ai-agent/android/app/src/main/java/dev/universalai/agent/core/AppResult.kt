package dev.universalai.agent.core

/** A tiny result type used by view models so UI state never carries raw exceptions. */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val message: String, val code: String = "error", val retryable: Boolean = true) :
        AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun valueOrNull(): T? = (this as? Success)?.value
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

/** Standard loading state for a screen section. */
sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Error(val message: String, val retryable: Boolean = true) : Loadable<Nothing>

    fun valueOrNull(): T? = (this as? Ready)?.value
}
