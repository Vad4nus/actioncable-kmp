package io.github.vad4nus.actioncable

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun e2eEngine(): HttpClientEngineFactory<HttpClientEngineConfig> = OkHttp

internal actual val unauthorizedRuns: Int = 1

internal actual val largeEchoBytes: Int = 2 * 1024 * 1024

internal actual fun blockedEngineThreads(): Int = Thread.getAllStackTraces().count { (thread, stack) ->
    thread.name.startsWith("OkHttp") && stack.any { it.methodName == "trySendBlocking" }
}
