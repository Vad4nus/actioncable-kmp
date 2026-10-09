package io.github.vad4nus.actioncable.internal.log

import io.github.vad4nus.actioncable.CableLogger
import platform.Foundation.NSLog

internal actual fun platformLog(level: CableLogger.Level, message: String) {
    NSLog("%@", "[$PLATFORM_LOG_TAG] ${level.name}: $message")
}
