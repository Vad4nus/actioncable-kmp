package io.github.vad4nus.actioncable

import io.ktor.http.Url
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateCoroutinesApi::class)
class MultiClientTest {

    private val room = buildJsonObject {
        put("channel", "RoomChannel")
        put("room", "shared")
    }

    @Test
    fun eachClientUsesItsOwnRequestAndLogsItsOwnHost() = realTest {
        val logger = RecordingCableLogger()
        val a = ClientFixture(url = "ws://a.test/cable", onRequest = { headers["X-Client"] = "a" }, logger = logger)
        val b = ClientFixture(url = "ws://b.test/cable", onRequest = { headers["X-Client"] = "b" }, logger = logger)
        try {
            a.client.connect()
            b.client.connect()
            a.connected()
            b.connected()

            for ((fx, name) in listOf(a to "a", b to "b")) {
                val requests = fx.server.requests()
                assertEquals(1, requests.size, name)
                assertEquals("$name.test", Url(requests.single().url).host)
                assertEquals(listOf(name), requests.single().headers["X-Client"])
                assertTrue(logger.lines().any { "host=$name.test" in it }, name)
            }
            val hosted = logger.lines().filter { "host=" in it }
            assertTrue(hosted.all { "host=a.test" in it || "host=b.test" in it }, hosted.toString())
        } finally {
            a.client.close()
            b.client.close()
            a.awaitReleased()
            b.awaitReleased()
        }
    }

    @Test
    fun backoffIsPerClient() = realTest {
        val fast = ClientFixture(options = CableOptions().apply { minReconnectDelay = 50.milliseconds })
        val slow = ClientFixture(options = CableOptions().apply { minReconnectDelay = 1.seconds })
        fast.server.failNext(1_000)
        slow.server.failNext(1_000)
        try {
            fast.client.connect()
            withTimeout(5.seconds) { fast.server.awaitCalls(1) }
            slow.client.connect()
            withTimeout(5.seconds) { fast.server.awaitCalls(3) }
            withTimeout(5.seconds) { slow.server.awaitCalls(2) }

            val f = fast.server.callMarks()
            val s = slow.server.callMarks()
            assertTrue(s[1] - s[0] >= 750.milliseconds, "slow: ${s[1] - s[0]}")
            assertTrue(f[2] - f[0] < s[1] - s[0], "fast: ${f[2] - f[0]}, slow: ${s[1] - s[0]}")
        } finally {
            fast.client.close()
            slow.client.close()
            fast.awaitReleased()
            slow.awaitReleased()
        }
    }

    @Test
    fun sameIdentifierOnTwoClientsIsRoutedPerClient() = realTest {
        val a = ClientFixture()
        val b = ClientFixture()
        try {
            a.client.connect()
            b.client.connect()
            val sa = a.connected()
            val sb = b.connected()
            val ha = a.subscribed(room, sa)
            val hb = b.subscribed(room, sb)

            val received = async { ha.messages.first() }
            sa.serverSend("""{"identifier":${JsonPrimitive(ha.identifier)},"message":{"n":1}}""")

            assertEquals(buildJsonObject { put("n", 1) }, withTimeout(5.seconds) { received.await() })
            assertNull(withTimeoutOrNull(200.milliseconds) { hb.messages.first() })
        } finally {
            a.client.close()
            b.client.close()
            a.awaitReleased()
            b.awaitReleased()
        }
    }

    @Test
    fun oneClientsLifecycleNeverTouchesAnother() = realTest {
        for (case in listOf("disconnect", "close", "gate", "stopped")) {
            val gate = MutableStateFlow(true)
            val a = ClientFixture(canConnect = gate)
            val b = ClientFixture()
            try {
                a.client.connect()
                b.client.connect()
                val sa = a.connected()
                val sb = b.connected()
                a.subscribed(room, sa)
                val hb = b.subscribed(room, sb)

                when (case) {
                    "disconnect" -> a.client.disconnect()
                    "close" -> a.client.close()
                    "gate" -> gate.value = false
                    else -> sa.serverSend("""{"type":"disconnect","reason":"invalid_request","reconnect":false}""")
                }
                val expected = when (case) {
                    "close" -> CableState.Closed
                    "stopped" -> CableState.Stopped("invalid_request")
                    else -> CableState.Disconnected
                }
                withTimeout(5.seconds) { a.client.state.first { it == expected } }
                if (case == "close") a.awaitReleased()
                delay(100.milliseconds)

                assertEquals(CableState.Connected, b.client.state.value, case)
                assertEquals(SubscriptionState.Subscribed(1), hb.state.value, case)
                assertTrue(hb.perform("speak"), case)
                assertEquals(if (case == "close") 1 else 0, a.releaseCount, case)
                assertEquals(0, b.releaseCount, case)
            } finally {
                a.client.close()
                b.client.close()
                a.awaitReleased()
                b.awaitReleased()
            }
        }
    }

    @Test
    fun twentyClientsConnectAndCloseConcurrently() = realTest {
        val pool = newFixedThreadPoolContext(8, "multi-client")
        try {
            val fixtures = (1..20).map { i ->
                async(pool) {
                    val fx = ClientFixture(url = "ws://c$i.test/cable")
                    fx.client.connect()
                    fx.connected()
                    fx.client.close()
                    fx
                }
            }.awaitAll()

            for (fx in fixtures) {
                fx.awaitReleased()
                assertEquals(CableState.Closed, fx.client.state.value)
            }
            delay(200.milliseconds)
            assertEquals(List(20) { 1 }, fixtures.map { it.releaseCount })
        } finally {
            pool.close()
        }
    }
}
