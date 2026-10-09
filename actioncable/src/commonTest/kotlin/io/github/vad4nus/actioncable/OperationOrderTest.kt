package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.protocol.encodeSubscribe
import io.github.vad4nus.actioncable.internal.protocol.identifierOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OperationOrderTest {

    private val room = buildJsonObject {
        put("channel", "RoomChannel")
        put("room", "a")
    }

    @Test
    fun unsubscribeThenSubscribeOfTheSameIdentifierNeverThrows() = realTest {
        val fx = ClientFixture()
        try {
            fx.client.connect()
            fx.connected()
            var handle = fx.client.subscribe(room)
            repeat(1_000) {
                fx.client.disconnect()
                handle.unsubscribe()
                handle = fx.client.subscribe(room)
            }
            assertEquals(SubscriptionState.Pending, handle.state.value)
        } finally {
            fx.client.close()
            fx.awaitReleased()
        }
    }

    @Test
    fun performAfterUnsubscribeReturnsFalseAndSendsNothing() = realTest {
        val fx = ClientFixture()
        try {
            fx.client.connect()
            val session = fx.connected()
            repeat(200) { round ->
                val handle = fx.subscribed(buildJsonObject {
                    put("channel", "RoomChannel")
                    put("round", round)
                }, session)
                handle.unsubscribe()
                assertFalse(handle.perform("speak"), "round $round")
            }
            assertEquals(0, session.allClientSent().count { it.contains("\"command\":\"message\"") })
        } finally {
            fx.client.close()
            fx.awaitReleased()
        }
    }

    @Test
    fun cancelledSubscribeCallersNeverLeaveAnOrphanRegistration() = realTest {
        val fx = ClientFixture()
        val random = Random(7)
        try {
            fx.client.connect()
            fx.connected()
            repeat(1_000) {
                val caller = launch {
                    val handle = fx.client.subscribe(room)
                    try {
                        awaitCancellation()
                    } finally {
                        handle.unsubscribe()
                    }
                }
                repeat(random.nextInt(0, 20)) { yield() }
                caller.cancelAndJoin()
                fx.client.subscribe(room).unsubscribe()
            }
        } finally {
            fx.client.close()
            fx.awaitReleased()
        }
    }

    @Test
    fun subscribeCancelledBeforeTheDrainRegistersNothing() = runTest {
        val control = StandardTestDispatcher(testScheduler)
        val fx = ClientFixture(dispatcher = control, callbackContext = control, random = Random(1), timeSource = testScheduler.timeSource)
        val subscribeFrame = encodeSubscribe(identifierOf(room))
        try {
            fx.client.connect()
            runCurrent()
            fx.server.welcome()
            runCurrent()
            assertEquals(CableState.Connected, fx.client.state.value)

            val caller = launch(Dispatchers.Unconfined) { fx.client.subscribe(room) }
            caller.cancel()
            runCurrent()
            assertEquals(0, fx.server.lastSession().allClientSent().count { it == subscribeFrame })

            val next = async(Dispatchers.Unconfined) { fx.client.subscribe(room) }
            runCurrent()
            assertEquals(SubscriptionState.Pending, next.await().state.value)
            assertEquals(1, fx.server.lastSession().allClientSent().count { it == subscribeFrame })
        } finally {
            fx.client.close()
            runCurrent()
        }
    }

    @Test
    fun subscribedHandleReturnsToPendingAndIsConfirmedAgain() = realTest {
        for (case in listOf("disconnect", "stopped", "max_reconnect_attempts")) {
            val fx = ClientFixture(options = CableOptions().apply { if (case == "max_reconnect_attempts") maxReconnectAttempts = 0 })
            try {
                fx.client.connect()
                val first = fx.connected()
                val handle = fx.subscribed(room, first)
                when (case) {
                    "disconnect" -> fx.client.disconnect()
                    "stopped" -> {
                        first.serverSend("""{"type":"disconnect","reason":"invalid_request","reconnect":false}""")
                        withTimeout(5.seconds) { fx.client.state.first { it == CableState.Stopped("invalid_request") } }
                    }
                    else -> {
                        first.closeGracefully()
                        withTimeout(5.seconds) { fx.client.state.first { it == CableState.Stopped("max_reconnect_attempts") } }
                    }
                }
                withTimeout(5.seconds) { handle.state.first { it == SubscriptionState.Pending } }

                fx.client.connect()
                val second = fx.connected(session = 2)
                withTimeout(5.seconds) { second.awaitSent { encodeSubscribe(handle.identifier) in it } }
                assertFalse(handle.perform("speak"), case)
                second.serverSend("""{"type":"confirm_subscription","identifier":${kotlinx.serialization.json.JsonPrimitive(handle.identifier)}}""")
                withTimeout(5.seconds) { handle.state.first { it == SubscriptionState.Subscribed(2) } }
                assertTrue(handle.perform("speak"), case)
            } finally {
                fx.client.close()
                fx.awaitReleased()
            }
        }
    }
}
