package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.client.ClientImpl
import io.github.vad4nus.actioncable.internal.client.validateConfig
import io.github.vad4nus.actioncable.internal.connection.KtorSessionOpener
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject

public interface ActionCableClient {
    public val state: StateFlow<CableState>
    public fun connect()
    public fun disconnect()
    public fun close()
    public suspend fun subscribe(identifier: JsonObject): CableSubscription
}

public fun ActionCableClient(
    url: String,
    onRequest: suspend HttpRequestBuilder.() -> Unit = {},
    onUnauthorized: suspend () -> Boolean = { false },
    canConnect: Flow<Boolean> = flowOf(true),
    options: CableOptions.() -> Unit = {},
    httpClientConfig: HttpClientConfig<*>.() -> Unit = {},
): ActionCableClient = newClient(url, onRequest, onUnauthorized, canConnect, options) {
    HttpClient {
        install(WebSockets)
        httpClientConfig()
    }
}

public fun <T : HttpClientEngineConfig> ActionCableClient(
    url: String,
    engine: HttpClientEngineFactory<T>,
    onRequest: suspend HttpRequestBuilder.() -> Unit = {},
    onUnauthorized: suspend () -> Boolean = { false },
    canConnect: Flow<Boolean> = flowOf(true),
    options: CableOptions.() -> Unit = {},
    httpClientConfig: HttpClientConfig<T>.() -> Unit = {},
): ActionCableClient = newClient(url, onRequest, onUnauthorized, canConnect, options) {
    HttpClient(engine) {
        install(WebSockets)
        httpClientConfig()
    }
}

private inline fun newClient(
    url: String,
    noinline onRequest: suspend HttpRequestBuilder.() -> Unit,
    noinline onUnauthorized: suspend () -> Boolean,
    canConnect: Flow<Boolean>,
    options: CableOptions.() -> Unit,
    httpClient: () -> HttpClient,
): ActionCableClient {
    val cableOptions = CableOptions().apply(options)
    validateConfig(url, cableOptions)
    val http = httpClient()
    return ClientImpl(
        url = url,
        onRequest = onRequest,
        onUnauthorized = onUnauthorized,
        canConnect = canConnect,
        options = cableOptions,
        opener = KtorSessionOpener(http),
        release = http::close,
    )
}
