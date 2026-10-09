package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.connection.cableRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CableRequestTest {

    @Test
    fun setsActionCableProtocolHeader() = runTest {
        val req = HttpRequestBuilder()
        req.cableRequest("ws://example.com/cable") {}
        val values = req.headers.getAll(HttpHeaders.SecWebSocketProtocol)
        assertEquals(listOf("actioncable-v1-json, actioncable-unsupported"), values)
    }

    @Test
    fun onRequestCanReplaceProtocolHeader() = runTest {
        val req = HttpRequestBuilder()
        req.cableRequest("ws://example.com/cable") {
            headers[HttpHeaders.SecWebSocketProtocol] = "custom-protocol"
        }
        val values = req.headers.getAll(HttpHeaders.SecWebSocketProtocol)
        assertEquals(listOf("custom-protocol"), values)
    }

    @Test
    fun onRequestReadsDifferentValuesAcrossCalls() = runTest {
        var counter = 0
        val results = (1..2).map {
            val req = HttpRequestBuilder()
            req.cableRequest("ws://example.com/cable") {
                counter++
                headers["X-Counter"] = counter.toString()
            }
            req.headers["X-Counter"]
        }
        assertNotEquals(results[0], results[1])
    }
}
