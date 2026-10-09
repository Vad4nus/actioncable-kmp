package io.github.vad4nus.actioncable

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class SubscriptionReceiptTest {

    private val idA = """{"channel":"A"}"""
    private val idAB = """{"channel":"AB"}"""
    private val idARoom1 = """{"channel":"A","room":1}"""
    private val idARoom2 = """{"channel":"A","room":2}"""
    private val idCNamedA = """{"channel":"C","name":"A"}"""
    private val quiet = CableOptions().apply { staleTimeout = 10.minutes }

    @Test
    fun routingIsExactByIdentifierString() = subscriptionTest { h ->
        h.connect()
        val ids = listOf(idA, idAB, idARoom1, idARoom2, idCNamedA)
        val subs = ids.map { h.subscribe(it) }
        h.settle()

        ids.forEachIndexed { i, id ->
            h.server.confirm(id)
            h.settle()
            subs.forEachIndexed { j, sub ->
                val expected = if (j <= i) SubscriptionState.Subscribed(1) else SubscriptionState.Pending
                assertEquals(expected, sub.state.value, "handle $j after confirming $i")
            }
        }

        ids.forEachIndexed { i, id -> h.server.broadcast(id, JsonPrimitive(i)) }
        h.settle()
        subs.forEachIndexed { i, sub ->
            assertEquals(JsonPrimitive(i), sub.channel.tryReceive().getOrNull(), "handle $i")
            assertTrue(sub.channel.tryReceive().isFailure, "handle $i got a foreign message")
        }
    }

    @Test
    fun eachHandleGetsOnlyItsOwnReceiptsAndUnknownIdentifiersChangeNothing() = subscriptionTest { h ->
        h.connect()
        val a = h.subscribe(idA)
        val ab = h.subscribe(idAB)
        h.settle()

        h.server.confirm(idA)
        h.settle()
        assertEquals(SubscriptionState.Subscribed(1), a.state.value)
        assertEquals(SubscriptionState.Pending, ab.state.value)

        h.server.reject(idAB)
        h.settle()
        assertEquals(SubscriptionState.Subscribed(1), a.state.value)
        assertEquals(SubscriptionState.Rejected, ab.state.value)

        val unknown = """{"channel":"Zeta"}"""
        h.server.confirm(unknown)
        h.server.reject(unknown)
        h.server.broadcast(unknown, JsonPrimitive(1))
        h.settle()

        assertEquals(SubscriptionState.Subscribed(1), a.state.value)
        assertEquals(SubscriptionState.Rejected, ab.state.value)
        assertTrue(a.channel.tryReceive().isFailure)
        assertTrue(ab.channel.tryReceive().isFailure)
        assertEquals(CableState.Connected, h.state.value)
        assertEquals(1, h.server.calls)
        assertTrue(h.log.entries.none { it.message.contains("Zeta") })
        assertTrue(h.log.entries.any { it.message.startsWith("frame_ignored") })
    }

    @Test
    fun rejectIsNotResentOnSameConnectionAndIsResentAfterNextWelcome() = subscriptionTest(quiet) { h ->
        h.connect()
        val sub = h.subscribe(idA)
        h.settle()

        h.server.reject(idA)
        h.settle()
        assertEquals(SubscriptionState.Rejected, sub.state.value)

        h.advance(120_000)
        assertEquals(1, h.subscribes(idA))
        assertEquals(SubscriptionState.Rejected, sub.state.value)

        h.reconnect()
        assertEquals(2, h.server.sessions().size)
        assertEquals(SubscriptionState.Pending, sub.state.value)
        assertEquals(1, h.subscribes(idA))
    }

    @Test
    fun serverApplyingUnsubscribeBeforeSubscribeGetsOneOrphanUnsubscribe() = subscriptionTest(quiet) { h ->
        h.connect()
        val sub = h.subscribe(idA)
        h.settle()

        sub.unsubscribe()
        h.settle()
        assertEquals(SubscriptionState.Unsubscribed, sub.state.value)
        assertEquals(1, h.unsubscribes(idA))

        h.server.confirm(idA)
        h.settle()

        assertEquals(2, h.unsubscribes(idA))
        assertEquals(1, h.subscribes(idA))
        assertEquals(SubscriptionState.Unsubscribed, sub.state.value)
    }
}
