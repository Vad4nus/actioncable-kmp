package io.github.vad4nus.actioncable.internal.log

import android.util.Log
import io.github.vad4nus.actioncable.CableLogger

internal actual fun platformLog(level: CableLogger.Level, message: String) {
    Log.println(level.priority(), PLATFORM_LOG_TAG, message)
}

private fun CableLogger.Level.priority(): Int = when (this) {
    CableLogger.Level.DEBUG -> Log.DEBUG
    CableLogger.Level.INFO -> Log.INFO
    CableLogger.Level.WARN -> Log.WARN
    CableLogger.Level.ERROR -> Log.ERROR
}
