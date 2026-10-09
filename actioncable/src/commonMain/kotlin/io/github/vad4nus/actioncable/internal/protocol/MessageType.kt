package io.github.vad4nus.actioncable.internal.protocol

internal enum class MessageType(val value: String) {
    WELCOME("welcome"),
    PING("ping"),
    DISCONNECT("disconnect"),
    CONFIRM_SUBSCRIPTION("confirm_subscription"),
    REJECT_SUBSCRIPTION("reject_subscription"),
    ;

    companion object {
        fun of(value: String?): MessageType? = entries.firstOrNull { it.value == value }
    }
}
