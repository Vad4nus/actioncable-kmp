package io.github.vad4nus.actioncable.internal.log

import io.github.vad4nus.actioncable.internal.connection.StaleTimeoutException
import io.github.vad4nus.actioncable.internal.protocol.DisconnectReason
import io.github.vad4nus.actioncable.internal.protocol.MessageType
import io.ktor.http.Url
import io.ktor.http.hostWithPortIfSpecified

internal object LogRedaction {
    private const val CAUSE_WALK_LIMIT = 8
    private const val CLASS_CHAIN_LENGTH = 3
    private const val CLASS_CHAIN_SEPARATOR = "<"
    private const val SERIAL_PREFIX = "#"

    fun reason(reason: String?): String = when {
        reason == null -> LogPlaceholder.NONE.value
        DisconnectReason.of(reason) != null -> reason
        else -> LogPlaceholder.OTHER.value
    }

    fun type(type: String?): String = when {
        type == null -> LogPlaceholder.NONE.value
        MessageType.of(type) != null -> type
        else -> LogPlaceholder.OTHER.value
    }

    fun sub(serialNumber: Int): String = "$SERIAL_PREFIX$serialNumber"

    fun host(url: String): String =
        runCatching { Url(url).hostWithPortIfSpecified }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: LogPlaceholder.UNKNOWN_HOST.value

    fun classChain(e: Throwable): String = generateSequence(e) { it.cause }
        .take(CAUSE_WALK_LIMIT)
        .filterNot(::isRecoveredCopy)
        .take(CLASS_CHAIN_LENGTH)
        .joinToString(CLASS_CHAIN_SEPARATOR, transform = ::className)

    private fun className(t: Throwable): String =
        if (t is StaleTimeoutException) StaleTimeoutException.LOG_NAME
        else t::class.simpleName ?: LogPlaceholder.UNKNOWN_CLASS.value

    private fun isRecoveredCopy(t: Throwable): Boolean {
        val cause = t.cause ?: return false
        return cause::class == t::class && cause.message == t.message
    }
}
