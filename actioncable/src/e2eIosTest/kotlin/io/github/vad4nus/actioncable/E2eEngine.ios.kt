package io.github.vad4nus.actioncable

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual fun e2eEngine(): HttpClientEngineFactory<HttpClientEngineConfig> = Darwin

internal actual val unauthorizedRuns: Int = 50

// Ktor Darwin before 3.4 sets maximumMessageSize after task.resume(), so NSURLSessionWebSocketTask keeps its 1 MB cap; raise to 2 MB once the floor engine is 3.4+
internal actual val largeEchoBytes: Int = 960 * 1024

internal actual fun blockedEngineThreads(): Int = 0
