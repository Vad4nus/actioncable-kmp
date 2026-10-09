package io.github.vad4nus.actioncable.internal.connection

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.websocket.WebSocketSession

internal fun interface SessionOpener {
    suspend fun withSession(request: HttpRequestBuilder, block: suspend (WebSocketSession) -> Unit)
}
