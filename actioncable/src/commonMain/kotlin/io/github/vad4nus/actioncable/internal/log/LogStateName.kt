package io.github.vad4nus.actioncable.internal.log

import io.github.vad4nus.actioncable.CableState

internal enum class LogStateName(val value: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    STOPPED("Stopped"),
    CLOSED("Closed"),
    ;

    companion object {
        fun of(state: CableState): LogStateName = when (state) {
            CableState.Disconnected -> DISCONNECTED
            is CableState.Connecting -> CONNECTING
            CableState.Connected -> CONNECTED
            is CableState.Stopped -> STOPPED
            CableState.Closed -> CLOSED
        }
    }
}
