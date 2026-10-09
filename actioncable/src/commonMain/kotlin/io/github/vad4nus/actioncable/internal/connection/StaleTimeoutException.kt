package io.github.vad4nus.actioncable.internal.connection

internal class StaleTimeoutException : Exception() {
    companion object {
        const val LOG_NAME = "StaleTimeoutException"
    }
}
