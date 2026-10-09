package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.client.ClientOperation
import io.github.vad4nus.actioncable.internal.log.LogEvent
import io.github.vad4nus.actioncable.internal.log.LogField
import io.github.vad4nus.actioncable.internal.log.LogRedaction
import io.github.vad4nus.actioncable.internal.log.LogStateName
import kotlin.concurrent.Volatile

public object CableLog {

    @Volatile
    public var logger: CableLogger = CableLogger.None

    internal fun d(event: LogEvent, vararg fields: Pair<LogField, Any?>) =
        emit(CableLogger.Level.DEBUG, event, fields)

    internal fun i(event: LogEvent, vararg fields: Pair<LogField, Any?>) =
        emit(CableLogger.Level.INFO, event, fields)

    internal fun w(event: LogEvent, vararg fields: Pair<LogField, Any?>) =
        emit(CableLogger.Level.WARN, event, fields)

    internal fun e(event: LogEvent, vararg fields: Pair<LogField, Any?>) =
        emit(CableLogger.Level.ERROR, event, fields)

    internal fun state(host: String, state: CableState) {
        val hostField = LogField.HOST to host
        val nameField = LogField.STATE to LogStateName.of(state).value
        when (state) {
            is CableState.Connecting -> i(LogEvent.STATE, hostField, nameField, LogField.ATTEMPT to state.attempt)
            is CableState.Stopped -> i(LogEvent.STATE, hostField, nameField, LogField.REASON to LogRedaction.reason(state.reason))
            CableState.Connected, CableState.Disconnected, CableState.Closed -> i(LogEvent.STATE, hostField, nameField)
        }
    }

    internal fun frameIgnored(type: String?) = d(LogEvent.FRAME_IGNORED, LogField.TYPE to LogRedaction.type(type))

    internal fun closedCall(operation: ClientOperation) = w(LogEvent.CLOSED_CALL, LogField.OP to operation.value)

    private fun emit(level: CableLogger.Level, event: LogEvent, fields: Array<out Pair<LogField, Any?>>) {
        runCatching {
            logger.log(level, buildString {
                append(event.value)
                for ((field, value) in fields) {
                    append(' ').append(field.value).append('=').append(value)
                }
            })
        }
    }
}
