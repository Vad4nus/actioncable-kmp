package io.github.vad4nus.actioncable.internal.protocol

internal enum class CableSubprotocol(val value: String) {
    V1_JSON("actioncable-v1-json"),
    UNSUPPORTED("actioncable-unsupported"),
    ;

    companion object {
        val header: String = entries.joinToString(", ") { it.value }
    }
}
