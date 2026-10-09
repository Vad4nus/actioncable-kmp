package io.github.vad4nus.actioncable.internal.connection

import io.github.vad4nus.actioncable.internal.protocol.CableSubprotocol
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders

internal suspend fun HttpRequestBuilder.cableRequest(
    url: String,
    onRequest: suspend HttpRequestBuilder.() -> Unit,
) {
    url(url)
    headers {
        set(HttpHeaders.SecWebSocketProtocol, CableSubprotocol.header)
    }
    onRequest()
}
