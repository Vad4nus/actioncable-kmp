package io.github.vad4nus.actioncable

import java.lang.ref.WeakReference

internal actual fun liveThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive }

internal actual fun collectGarbage() = System.gc()

internal actual fun weakRef(value: Any): () -> Any? = WeakReference(value)::get
