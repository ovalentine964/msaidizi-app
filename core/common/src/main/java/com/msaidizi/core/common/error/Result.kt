package com.msaidizi.core.common.error

/**
 * Compact error representation for crash reporting.
 *
 * Minimizes error data size for storage and transmission while
 * preserving enough information for debugging. Used by the
 * superagent's safety guard and crash logger.
 */
data class CompactError(
    /** Error category */
    val category: ErrorCategory,
    /** Short error code (e.g., "TXN_001", "NET_002") */
    val code: String,
    /** Compact message (max 100 chars) */
    val message: String,
    /** Timestamp */
    val timestamp: Long = System.currentTimeMillis(),
    /** Stack trace hash (for deduplication) */
    val stackHash: String = "",
    /** Additional context as key-value pairs */
    val context: Map<String, String> = emptyMap()
)

/**
 * Error categories for classification.
 */
enum class ErrorCategory {
    /** Transaction processing error */
    TRANSACTION,
    /** Network/sync error */
    NETWORK,
    /** Database error */
    DATABASE,
    /** Voice processing error */
    VOICE,
    /** Security/auth error */
    SECURITY,
    /** Validation error */
    VALIDATION,
    /** LLM inference error */
    INFERENCE,
    /** Unknown/unclassified */
    UNKNOWN
}

/**
 * Error compactor — reduces error information to minimal form.
 */
object ErrorCompactor {

    private const val MAX_MESSAGE_LENGTH = 100

    /**
     * Compact an exception into a CompactError.
     */
    fun compact(
        throwable: Throwable,
        category: ErrorCategory = ErrorCategory.UNKNOWN,
        context: Map<String, String> = emptyMap()
    ): CompactError {
        val message = throwable.message?.take(MAX_MESSAGE_LENGTH) ?: "Unknown error"
        val stackHash = throwable.stackTrace.contentHashCode().toString(16)
        val code = generateCode(category, throwable)

        return CompactError(
            category = category,
            code = code,
            message = message,
            stackHash = stackHash,
            context = context
        )
    }

    /**
     * Generate a compact error code from category and exception type.
     */
    private fun generateCode(category: ErrorCategory, throwable: Throwable): String {
        val prefix = when (category) {
            ErrorCategory.TRANSACTION -> "TXN"
            ErrorCategory.NETWORK -> "NET"
            ErrorCategory.DATABASE -> "DB"
            ErrorCategory.VOICE -> "VOC"
            ErrorCategory.SECURITY -> "SEC"
            ErrorCategory.VALIDATION -> "VAL"
            ErrorCategory.INFERENCE -> "INF"
            ErrorCategory.UNKNOWN -> "UNK"
        }
        val suffix = throwable::class.simpleName?.take(4)?.uppercase() ?: "ERR"
        return "${prefix}_${suffix}"
    }

    /**
     * Compact multiple errors into a batch summary.
     */
    fun compactBatch(errors: List<CompactError>): Map<ErrorCategory, Int> {
        return errors.groupBy { it.category }.mapValues { it.value.size }
    }
}

/**
 * Sealed class for operation results.
 * Replaces try-catch with explicit success/failure handling.
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Failure(val error: CompactError) : Result<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.data

    fun errorOrNull(): CompactError? = (this as? Failure)?.error

    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onFailure(action: (CompactError) -> Unit): Result<T> {
        if (this is Failure) action(error)
        return this
    }

    companion object {
        fun <T> success(data: T): Result<T> = Success(data)
        fun failure(
            category: ErrorCategory,
            code: String,
            message: String
        ): Result<Nothing> = Failure(CompactError(category, code, message))

        fun failure(throwable: Throwable, category: ErrorCategory): Result<Nothing> =
            Failure(ErrorCompactor.compact(throwable, category))
    }
}
