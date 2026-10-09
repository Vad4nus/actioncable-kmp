package io.github.vad4nus.actioncable.internal.connection

import kotlin.time.ComparableTimeMark

internal class SessionState(private val start: ComparableTimeMark) {
    var welcomed = false
    var healthy = false
    var lastFrame: ComparableTimeMark = start

    fun deadlineBase(): ComparableTimeMark = if (welcomed) lastFrame else start
}
