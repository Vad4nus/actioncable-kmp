package io.github.vad4nus.actioncable.internal.connection

internal sealed interface AttemptOutcome {
    data object Failure : AttemptOutcome
    data class Healthy(val delay: Boolean) : AttemptOutcome
    data class Stop(val reason: String?) : AttemptOutcome
    data class Unauthorized(val reason: String?) : AttemptOutcome
}
