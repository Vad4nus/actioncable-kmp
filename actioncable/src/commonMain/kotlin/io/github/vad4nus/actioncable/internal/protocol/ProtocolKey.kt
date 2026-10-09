package io.github.vad4nus.actioncable.internal.protocol

internal enum class ProtocolKey(val value: String) {
    TYPE("type"),
    COMMAND("command"),
    IDENTIFIER("identifier"),
    MESSAGE("message"),
    DATA("data"),
    ACTION("action"),
    CHANNEL("channel"),
    REASON("reason"),
    RECONNECT("reconnect"),
}
