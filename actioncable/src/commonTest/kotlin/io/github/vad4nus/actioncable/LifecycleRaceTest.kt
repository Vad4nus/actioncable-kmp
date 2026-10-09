package io.github.vad4nus.actioncable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(DelicateCoroutinesApi::class)
class LifecycleRaceTest {

    private fun room(name: String) = buildJsonObject {
        put("channel", "RoomChannel")
        put("room", name)
    }

    @Test
    fun concurrentConnectsOpenOneSession() = realTest {
        val fx = ClientFixture()
        val pool = newFixedThreadPoolContext(8, "connect-race")
        try {
            (1..100).map { launch(pool) { fx.client.connect() } }.joinAll()
            fx.connected()
            delay(200.milliseconds)

            assertEquals(1, fx.server.calls)
            assertEquals(1, fx.server.openSessions())
        } finally {
            pool.close()
            fx.client.close()
            fx.awaitReleased()
        }
    }

    @Test
    fun interleavedConnectAndDisconnectLeaveAtMostOneSession() = realTest {
        val fx = ClientFixture()
        val pool = newFixedThreadPoolContext(4, "toggle-race")
        try {
            (0 until 4).map { thread ->
                launch(pool) {
                    val random = Random(thread)
                    repeat(250) { i ->
                        if ((thread + i) % 2 == 0) fx.client.connect() else fx.client.disconnect()
                        delay(random.nextLong(0, 3).milliseconds)
                    }
                }
            }.joinAll()
            fx.client.subscribe(room("barrier"))
            delay(300.milliseconds)

            val ended = fx.server.sessions().count { it.ended.value }
            assertTrue(ended > 0, "calls=${fx.server.calls} ended=$ended")
            assertTrue(fx.server.openSessions() <= 1, "open=${fx.server.openSessions()}")
            assertEquals(ended, fx.server.cancellations)
        } finally {
            pool.close()
            fx.client.close()
            fx.awaitReleased()
        }
    }

    @Test
    fun connectRightAfterStoppedOpensExactlyOneNewSession() = realTest {
        val fx = ClientFixture()
        try {
            fx.client.connect()
            val first = fx.connected()
            first.serverSend("""{"type":"disconnect","reason":"invalid_request","reconnect":false}""")
            withTimeout(5.seconds) { fx.client.state.first { it is CableState.Stopped } }
            fx.client.connect()
            fx.connected(session = 2)
            delay(200.milliseconds)

            assertEquals(2, fx.server.calls)
            assertTrue(fx.server.callMarks()[1] > fx.server.endMarks()[0])
            assertEquals(1, fx.server.openSessions())
            assertEquals(CableState.Connected, fx.client.state.value)
        } finally {
            fx.client.close()
            fx.awaitReleased()
        }
    }

    @Test
    fun closeWhileTheOpenerIsSuspended() = realTest {
        val fx = ClientFixture()
        fx.server.hangNext()
        fx.client.connect()
        withTimeout(5.seconds) { fx.server.awaitCalls(1) }

        fx.client.close()

        fx.assertReleasedOnce()
        assertEquals(CableState.Closed, fx.client.state.value)
        assertEquals(1, fx.server.cancellations)
        assertEquals(1, fx.server.calls)
    }

    @Test
    fun blockingCallbacksNeverDelaySubscribe() = realTest {
        for (kind in listOf("onRequest", "onUnauthorized", "canConnect")) {
            val blocking = CompletableDeferred<Unit>()
            val block = {
                blocking.complete(Unit)
                spin(2.seconds)
            }
            val fx = when (kind) {
                "onRequest" -> ClientFixture(onRequest = { block() })
                "onUnauthorized" -> ClientFixture(onUnauthorized = { block(); true })
                else -> ClientFixture(canConnect = flow { block(); emit(true) })
            }
            fx.client.connect()
            if (kind == "onUnauthorized") {
                fx.connected().serverSend("""{"type":"disconnect","reason":"unauthorized","reconnect":false}""")
            }
            withTimeout(5.seconds) { blocking.await() }

            val mark = TimeSource.Monotonic.markNow()
            fx.client.subscribe(room(kind))
            val took = mark.elapsedNow()

            assertTrue(took < 200.milliseconds, "$kind: $took")
            fx.client.close()
            fx.awaitReleased(timeout = 10.seconds)
        }
    }
}
