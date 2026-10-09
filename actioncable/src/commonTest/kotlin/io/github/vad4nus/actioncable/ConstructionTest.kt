package io.github.vad4nus.actioncable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ConstructionTest {

    private val valid = "wss://a.test/cable"

    @Test
    fun urlsThatAreNotWsOrWssAreRejected() {
        val urls = listOf(
            "https://a.test/cable",
            "http://a.test/cable",
            "a.test/cable",
            "",
            "wss://",
            "wss://a.test:port/cable",
        )
        for (url in urls) assertRejected("invalid_url", url) {}
    }

    @Test
    fun invalidOptionsAreRejected() {
        val options: List<CableOptions.() -> Unit> = listOf(
            { staleTimeout = Duration.ZERO },
            { staleTimeout = (-1).seconds },
            { minReconnectDelay = Duration.ZERO },
            { maxReconnectDelay = Duration.ZERO },
            { minReconnectDelay = 5.seconds; maxReconnectDelay = 1.seconds },
            { maxReconnectAttempts = -1 },
            { messageBufferSize = 0 },
        )
        for (option in options) assertRejected("invalid_option", valid, option)
    }

    @Test
    fun defaultsAndZeroRetriesAreAccepted() {
        val defaults = ActionCableClient(valid)
        val noRetries = ActionCableClient("ws://a.test:8080/cable", options = { maxReconnectAttempts = 0 })
        assertEquals(CableState.Disconnected, defaults.state.value)
        assertEquals(CableState.Disconnected, noRetries.state.value)
        defaults.close()
        noRetries.close()
        assertEquals(CableState.Closed, defaults.state.value)
        assertEquals(CableState.Closed, noRetries.state.value)
    }

    @Test
    fun upperCaseSchemesAndEqualDelaysAreAccepted() {
        val clients = listOf(
            ActionCableClient("WSS://a.test/cable"),
            ActionCableClient("Ws://a.test/cable", options = { minReconnectDelay = 5.seconds; maxReconnectDelay = 5.seconds }),
        )
        for (client in clients) {
            assertEquals(CableState.Disconnected, client.state.value)
            client.close()
        }
    }

    private fun assertRejected(code: String, url: String, options: CableOptions.() -> Unit) {
        val e = assertFailsWith<IllegalArgumentException>(url) { ActionCableClient(url, options = options) }
        assertTrue(e.message.orEmpty().startsWith("$code: "), e.message)
        assertNull(e.cause)
    }
}
