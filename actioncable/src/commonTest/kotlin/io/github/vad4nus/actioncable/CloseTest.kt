package io.github.vad4nus.actioncable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(DelicateCoroutinesApi::class)
class CloseTest {

    private fun room(name: String) = buildJsonObject {
        put("channel", "RoomChannel")
        put("room", name)
    }

    @Test
    fun closeCompletesMessagesAndUnsubscribesBeforeASingleRelease() = realTest {
        val atRelease = MutableStateFlow<List<SubscriptionState>?>(null)
        var handles = emptyList<CableSubscription>()
        val fx = ClientFixture(onRelease = { atRelease.value = handles.map { it.state.value } })
        fx.client.connect()
        val session = fx.connected()
        handles = listOf(fx.subscribed(room("a"), session), fx.subscribed(room("b"), session))
        val collectors = handles.map { handle -> async { handle.messages.toList() } }

        fx.client.close()

        assertEquals(CableState.Closed, fx.client.state.value)
        fx.assertReleasedOnce()
        withTimeout(5.seconds) { collectors.awaitAll() }
        assertTrue(handles.all { it.state.value == SubscriptionState.Unsubscribed })
        assertEquals(listOf(SubscriptionState.Unsubscribed, SubscriptionState.Unsubscribed), atRelease.value)
        assertEquals(1, fx.server.cancellations)
    }

    @Test
    fun subscribeRightAfterCloseReturnsAnUnsubscribedHandle() = realTest {
        val fx = ClientFixture()
        fx.client.connect()
        fx.client.close()
        val handle = fx.client.subscribe(room("a"))

        assertEquals(SubscriptionState.Unsubscribed, handle.state.value)
        fx.assertReleasedOnce()
    }

    @Test
    fun callsAfterCloseDoNothingAndLogClosedCall() = realTest {
        val fx = ClientFixture()
        fx.client.connect()
        val session = fx.connected()
        val handle = fx.subscribed(room("a"), session)

        fx.client.close()
        fx.client.close()
        fx.client.connect()
        fx.client.disconnect()
        assertFalse(handle.perform("speak"))
        handle.unsubscribe()

        fx.assertReleasedOnce()
        assertEquals(CableState.Closed, fx.client.state.value)
        assertEquals(
            listOf("closed_call op=close", "closed_call op=connect", "closed_call op=disconnect", "closed_call op=perform", "closed_call op=unsubscribe"),
            fx.logger.warns().filter { it.startsWith("closed_call ") },
        )
    }

    @Test
    fun closeBehindAHandlerThatIgnoresCancellation() = realTest {
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerReturned = MutableStateFlow(false)
        val releasedAfterHandler = MutableStateFlow<Boolean?>(null)
        val fx = ClientFixture(
            onUnauthorized = {
                handlerStarted.complete(Unit)
                withContext(NonCancellable) { delay(5.seconds) }
                handlerReturned.value = true
                true
            },
            onRelease = { releasedAfterHandler.value = handlerReturned.value },
        )
        fx.client.connect()
        val session = fx.connected()
        val handle = fx.subscribed(room("a"), session)
        session.serverSend("""{"type":"disconnect","reason":"unauthorized","reconnect":false}""")
        withTimeout(5.seconds) { handlerStarted.await() }

        fx.client.disconnect()
        val mark = TimeSource.Monotonic.markNow()
        val performed = async(start = CoroutineStart.UNDISPATCHED) { handle.perform("speak") }
        fx.client.close()

        assertFalse(withTimeout(1.seconds) { performed.await() })
        assertTrue(mark.elapsedNow() < 1.seconds)
        fx.awaitReleased(timeout = 10.seconds)
        assertEquals(true, releasedAfterHandler.value)
        delay(200.milliseconds)
        assertEquals(1, fx.releaseCount)
        assertEquals(CableState.Closed, fx.client.state.value)
    }

    @Test
    fun callsRacingCloseReturnPromptlyWithClosedAnswers() = realTest {
        val fx = ClientFixture()
        fx.client.connect()
        val session = fx.connected()
        val shared = fx.subscribed(room("shared"), session)
        val pool = newFixedThreadPoolContext(8, "close-race")
        try {
            val running = MutableStateFlow(true)
            val violations = MutableStateFlow(emptyList<String>())
            val workers = (0 until 8).map { worker ->
                launch(pool) {
                    var round = 0
                    while (running.value) {
                        val closedBefore = fx.client.state.value == CableState.Closed
                        val subscribeMark = TimeSource.Monotonic.markNow()
                        val handle = fx.client.subscribe(buildJsonObject {
                            put("channel", "RoomChannel")
                            put("worker", worker)
                            put("round", round++)
                        })
                        val subscribeTook = subscribeMark.elapsedNow()
                        val performMark = TimeSource.Monotonic.markNow()
                        val performed = shared.perform("speak")
                        val performTook = performMark.elapsedNow()
                        if (subscribeTook > 1.seconds) violations.update { it + "subscribe took $subscribeTook" }
                        if (performTook > 1.seconds) violations.update { it + "perform took $performTook" }
                        if (closedBefore && handle.state.value != SubscriptionState.Unsubscribed) violations.update { it + "live handle" }
                        if (closedBefore && performed) violations.update { it + "perform true" }
                        delay(1.milliseconds)
                    }
                }
            }
            delay(300.milliseconds)
            fx.client.close()
            delay(300.milliseconds)
            running.value = false
            workers.joinAll()

            assertEquals(emptyList(), violations.value)
            fx.assertReleasedOnce()
        } finally {
            pool.close()
        }
    }
}
