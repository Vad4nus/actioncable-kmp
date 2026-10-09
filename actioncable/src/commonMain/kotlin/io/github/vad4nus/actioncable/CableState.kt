package io.github.vad4nus.actioncable

public sealed interface CableState {
    public data object Disconnected : CableState
    public data class Connecting(val attempt: Int) : CableState
    public data object Connected : CableState
    public data class Stopped(val reason: String?) : CableState
    public data object Closed : CableState
}
