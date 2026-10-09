package io.github.vad4nus.actioncable.internal.protocol

internal enum class ProtocolCommand(val value: String) {
    SUBSCRIBE("subscribe"),
    UNSUBSCRIBE("unsubscribe"),
    MESSAGE("message"),
}
