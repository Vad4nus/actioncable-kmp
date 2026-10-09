package io.github.vad4nus.actioncable.internal

import io.github.vad4nus.actioncable.internal.protocol.ProtocolKey

internal object CableErrors {
    private const val INVALID_URL = "invalid_url"
    private const val INVALID_OPTION = "invalid_option"
    private const val UNENCODABLE_DATA = "unencodable_data"
    private const val MISSING_CHANNEL = "missing_channel"
    private const val DUPLICATE_IDENTIFIER = "duplicate_identifier"
    private const val CONCURRENT_COLLECTION = "concurrent_collection"
    private const val COMMAND_FAILED = "command_failed"

    fun invalidUrl(): IllegalArgumentException =
        IllegalArgumentException(message(INVALID_URL, "the url must be a valid ws or wss url"))

    fun notPositive(option: String): IllegalArgumentException =
        IllegalArgumentException(message(INVALID_OPTION, "$option must be positive"))

    fun exceeds(option: String, limit: String): IllegalArgumentException =
        IllegalArgumentException(message(INVALID_OPTION, "$option must not exceed $limit"))

    fun negative(option: String): IllegalArgumentException =
        IllegalArgumentException(message(INVALID_OPTION, "$option must not be negative"))

    fun belowOne(option: String): IllegalArgumentException =
        IllegalArgumentException(message(INVALID_OPTION, "$option must be at least 1"))

    fun unencodableData(): IllegalArgumentException =
        IllegalArgumentException(message(UNENCODABLE_DATA, "data contains a value that cannot be encoded as JSON"))

    fun unencodableIdentifier(): IllegalArgumentException =
        IllegalArgumentException(message(UNENCODABLE_DATA, "identifier contains a value that cannot be encoded as JSON"))

    fun missingChannel(): IllegalArgumentException =
        IllegalArgumentException(message(MISSING_CHANNEL, "identifier must contain a string '${ProtocolKey.CHANNEL.value}' key"))

    fun duplicateIdentifier(sub: String): IllegalStateException =
        IllegalStateException(message(DUPLICATE_IDENTIFIER, "identifier already registered as $sub"))

    fun concurrentCollection(sub: String): IllegalStateException =
        IllegalStateException(message(CONCURRENT_COLLECTION, "a second collector started before the first completed for $sub"))

    fun commandFailed(): IllegalStateException =
        IllegalStateException(message(COMMAND_FAILED, "the operation could not be applied"))

    private fun message(code: String, text: String): String = "$code: $text"
}
