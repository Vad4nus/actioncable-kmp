package io.github.vad4nus.actioncable.internal.log

internal enum class LogField(val value: String) {
    HOST("host"),
    STATE("state"),
    ATTEMPT("attempt"),
    REASON("reason"),
    CLASS("class"),
    TYPE("type"),
    SUB("sub"),
    DROPPED("dropped"),
    OP("op"),
}
