package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.protocol.encodeSubscribe
import io.github.vad4nus.actioncable.internal.protocol.identifierOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventOrderTest {

    private fun room(name: String) = buildJsonObject {
        put("channel", "RoomChannel")
        put("room", name)
    }

    private fun TestScope.fixture(): ClientFixture {
        val control = StandardTestDispatcher(testScheduler)
        return ClientFixture(dispatcher = control, callbackContext = control, random = Random(3), timeSource = testScheduler.timeSource)
    }

    private fun TestScope.connected(fx: ClientFixture) {
        fx.client.connect()
        runCurrent()
        fx.server.welcome()
        runCurrent()
        assertEquals(CableState.Connected, fx.client.state.value)
    }

    private suspend fun TestScope.subscribed(fx: ClientFixture, name: String): CableSubscription {
        val handle = async(Dispatchers.Unconfined) { fx.client.subscribe(room(name)) }
        runCurrent()
        fx.server.confirm(identifierOf(room(name)))
        runCurrent()
        return handle.await().also { assertEquals(SubscriptionState.Subscribed(1), it.state.value) }
    }

    private fun TestScope.close(fx: ClientFixture) {
        fx.client.close()
        runCurrent()
    }

    @Test
    fun stopFrameProcessedBeforeConnectIsFollowedByANewSession() = runTest {
        val fx = fixture()
        try {
            connected(fx)
            fx.server.disconnect("invalid_request")
            runCurrent()
            assertEquals(CableState.Stopped("invalid_request"), fx.client.state.value)

            fx.client.connect()
            runCurrent()
            assertEquals(2, fx.server.calls)
            assertTrue(fx.server.openSessions() <= 1)
            fx.server.welcome()
            runCurrent()

            assertEquals(CableState.Connected, fx.client.state.value)
            assertEquals(1, fx.server.openSessions())
        } finally {
            close(fx)
        }
    }

    @Test
    fun connectAppliedBeforeAStopFrameLeavesTheClientStopped() = runTest {
        val fx = fixture()
        try {
            connected(fx)
            fx.client.connect()
            runCurrent()
            fx.server.disconnect("invalid_request")
            runCurrent()
            advanceTimeBy(600_000)
            runCurrent()

            assertEquals(CableState.Stopped("invalid_request"), fx.client.state.value)
            assertEquals(1, fx.server.calls)
            assertEquals(0, fx.server.openSessions())
        } finally {
            close(fx)
        }
    }

    @Test
    fun subscribeNextToWelcomeInEitherOrderSendsOneSubscribe() = runTest {
        for (subscribeFirst in listOf(true, false)) {
            val fx = fixture()
            try {
                fx.client.connect()
                runCurrent()
                val session = fx.server.lastSession()
                val handle = if (subscribeFirst) {
                    async(Dispatchers.Unconfined) { fx.client.subscribe(room("a")) }.also { fx.server.welcome() }
                } else {
                    fx.server.welcome()
                    async(Dispatchers.Unconfined) { fx.client.subscribe(room("a")) }
                }
                runCurrent()

                assertEquals(CableState.Connected, fx.client.state.value)
                assertEquals(SubscriptionState.Pending, handle.await().state.value)
                val frames = session.allClientSent().count { it == encodeSubscribe(identifierOf(room("a"))) }
                assertEquals(1, frames, "subscribeFirst=$subscribeFirst")
            } finally {
                close(fx)
            }
        }
    }

    @Test
    fun commandsQueuedBeforeCloseGetClosedAnswers() = runTest {
        val fx = fixture()
        try {
            connected(fx)
            val handle = subscribed(fx, "a")

            val second = async(Dispatchers.Unconfined) { fx.client.subscribe(room("b")) }
            val performed = async(Dispatchers.Unconfined) { handle.perform("speak") }
            fx.client.close()
            runCurrent()

            assertEquals(SubscriptionState.Unsubscribed, second.await().state.value)
            assertFalse(performed.await())
            assertEquals(1, fx.releaseCount)
        } finally {
            close(fx)
        }
    }

    @Test
    fun stateCollectorSeesEveryHandlePendingWhenItObservesStoppedOrDisconnected() = runTest {
        for (stopped in listOf(true, false)) {
            val fx = fixture()
            try {
                connected(fx)
                val handles = listOf(subscribed(fx, "a"), subscribed(fx, "b"))
                val seen = mutableListOf<List<SubscriptionState>>()
                val collector = launch(Dispatchers.Unconfined) {
                    fx.client.state.collect { state ->
                        val target = if (stopped) state is CableState.Stopped else state == CableState.Disconnected
                        if (target) seen.add(handles.map { it.state.value })
                    }
                }
                if (stopped) fx.server.disconnect("invalid_request") else fx.client.disconnect()
                runCurrent()
                collector.cancel()

                assertEquals<List<List<SubscriptionState>>>(listOf(listOf(SubscriptionState.Pending, SubscriptionState.Pending)), seen, "stopped=$stopped")
            } finally {
                close(fx)
            }
        }
    }
}
