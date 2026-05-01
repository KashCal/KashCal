package org.onekash.icaldav.model

/**
 * Result type for parsing operations.
 * Provides detailed error information for debugging.
 */
sealed class ParseResult<out T> {
    data class Success<T>(val value: T) : ParseResult<T>()
    data class Error(val error: ParseError) : ParseResult<Nothing>()

    fun getOrNull(): T? = (this as? Success)?.value

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Error -> throw error.toException()
    }

    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Error -> default
    }

    inline fun <R> map(transform: (T) -> R): ParseResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Error -> this
    }

    inline fun <R> flatMap(transform: (T) -> ParseResult<R>): ParseResult<R> = when (this) {
        is Success -> transform(value)
        is Error -> this
    }

    inline fun onSuccess(action: (T) -> Unit): ParseResult<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onError(action: (ParseError) -> Unit): ParseResult<T> {
        if (this is Error) action(error)
        return this
    }

    companion object {
        fun <T> success(value: T): ParseResult<T> = Success(value)

        fun <T> error(message: String, cause: Throwable? = null): ParseResult<T> =
            Error(ParseError.General(message, cause))

        fun <T> invalidFormat(message: String, rawData: String? = null): ParseResult<T> =
            Error(ParseError.InvalidFormat(message, rawData))

        fun <T> missingProperty(property: String): ParseResult<T> =
            Error(ParseError.MissingProperty(property))
    }
}

/**
 * Detailed parse error types.
 */
sealed class ParseError {
    abstract val message: String

    data class InvalidFormat(
        override val message: String,
        val rawData: String? = null
    ) : ParseError()

    data class MissingProperty(
        val property: String
    ) : ParseError() {
        override val message: String = "Missing required property: $property"
    }

    data class InvalidProperty(
        val property: String,
        val value: String,
        override val message: String
    ) : ParseError()

    data class TimezoneError(
        val tzid: String,
        override val message: String
    ) : ParseError()

    data class General(
        override val message: String,
        val cause: Throwable? = null
    ) : ParseError()

    fun toException(): ParseException = ParseException(this)
}

class ParseException(val error: ParseError) : Exception(error.message)
