package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.log.platformLog

public fun interface CableLogger {
    public fun log(level: Level, message: String)

    public enum class Level { DEBUG, INFO, WARN, ERROR }

    public companion object {
        public val None: CableLogger = CableLogger { _, _ -> }
        public val Platform: CableLogger = CableLogger(::platformLog)
    }
}
