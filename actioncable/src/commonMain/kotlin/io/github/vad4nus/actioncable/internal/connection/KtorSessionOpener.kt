package io.github.vad4nus.actioncable.internal.connection

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.takeFrom
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

internal class KtorSessionOpener(private val client: HttpClient) : SessionOpener {
    override suspend fun withSession(request: HttpRequestBuilder, block: suspend (WebSocketSession) -> Unit) {
        val opened = CompletableDeferred<WebSocketSession>()
        val finished = CompletableDeferred<Unit>()
        // The handshake runs in the HttpClient's scope because cancelling it mid-handshake leaks (OkHttp's reader thread blocks forever through Ktor 3.3; Darwin now and then keeps the socket open, still on 3.5.2), at the cost of a stalled upgrade running until the engine's timeout; run it inline again once neither engine leaks at the Ktor floor
        client.launch {
            try {
                client.webSocket(request = { takeFrom(request) }) {
                    opened.complete(this)
                    finished.await()
                }
            } catch (e: Throwable) {
                opened.completeExceptionally(e)
            }
        }
        try {
            block(opened.await())
        } finally {
            finished.complete(Unit)
        }
    }
}
