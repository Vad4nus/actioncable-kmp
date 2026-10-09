package io.github.vad4nus.actioncable.internal.protocol

internal enum class DisconnectReason(val value: String) {
    UNAUTHORIZED("unauthorized"),
    INVALID_REQUEST("invalid_request"),
    SERVER_RESTART("server_restart"),
    REMOTE("remote"),
    TOKEN_EXPIRED("token_expired"),
    MAX_RECONNECT_ATTEMPTS("max_reconnect_attempts"),
    ;

    companion object {
        fun of(value: String?): DisconnectReason? = entries.firstOrNull { it.value == value }
    }
}
