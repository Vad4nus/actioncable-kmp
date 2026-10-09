package io.github.vad4nus.actioncable.internal.log

import io.github.vad4nus.actioncable.CableLogger

internal const val PLATFORM_LOG_TAG = "ActionCable"

internal expect fun platformLog(level: CableLogger.Level, message: String)
