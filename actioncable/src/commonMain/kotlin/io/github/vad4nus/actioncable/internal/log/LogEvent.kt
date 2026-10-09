package io.github.vad4nus.actioncable.internal.log

internal enum class LogEvent(val value: String) {
    STATE("state"),
    GATE_WAITING("gate_waiting"),
    GATE_FAILED("gate_failed"),
    REQUEST_CALLBACK_FAILED("request_callback_failed"),
    UNAUTHORIZED_CALLBACK_FAILED("unauthorized_callback_failed"),
    HANDSHAKE_FAILED("handshake_failed"),
    SESSION_FAILED("session_failed"),
    FRAME_IGNORED("frame_ignored"),
    BUFFER_OVERFLOW("buffer_overflow"),
    BUFFER_OVERFLOW_OPEN("buffer_overflow_open"),
    CLOSED_CALL("closed_call"),
    COMMAND_FAILED("command_failed"),
    RELEASE_FAILED("release_failed"),
    INTERNAL_ERROR("internal_error"),
}
