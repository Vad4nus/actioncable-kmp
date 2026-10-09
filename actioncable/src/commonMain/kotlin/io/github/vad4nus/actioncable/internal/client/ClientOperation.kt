package io.github.vad4nus.actioncable.internal.client

internal enum class ClientOperation(val value: String) {
    CONNECT("connect"),
    DISCONNECT("disconnect"),
    SUBSCRIBE("subscribe"),
    PERFORM("perform"),
    UNSUBSCRIBE("unsubscribe"),
    CLOSE("close"),
}
