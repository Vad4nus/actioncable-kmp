package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.protocol.encodePerform
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.currentTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class PerformTest {

    private val idA = """{"channel":"A"}"""
    private val idB = """{"channel":"B"}"""
    private val idC = """{"channel":"C"}"""
    private val idD = """{"channel":"D"}"""
    private val quiet = CableOptions().apply { staleTimeout = 10.minutes }

    private fun data(key: String, value: Int) = buildJsonObject { put(key, value) }

    private fun List<String>.performs() = filter { it.contains("\"command\":\"message\"") }

    private fun SubscriptionTestHarness.allPerforms() =
        server.sessions().flatMap { it.allClientSent() }.performs()

    private fun frame(identifier: String, data: JsonObject) = encodePerform(identifier, "act", data)

    @Test
    fun performIsTrueOnlyWhenSubscribedOnTheLiveSession() = subscriptionTest(quiet) { h ->
        val a = h.subscribe(idA)
        assertFalse(a.perform("act", data("x", 1)), "disconnected")

        h.connect()
        assertFalse(a.perform("act", data("x", 2)), "Pending")

        h.server.confirm(idA)
        h.settle()
        assertTrue(a.perform("act", data("x", 3)), "Subscribed")

        h.server.reject(idA)
        h.settle()
        assertFalse(a.perform("act", data("x", 4)), "Rejected")

        val b = h.subscribe(idB)
        h.settle()
        h.server.confirm(idB)
        h.settle()
        b.unsubscribe()
        assertFalse(b.perform("act", data("x", 5)), "Unsubscribed")

        val c = h.subscribe(idC)
        h.settle()
        h.server.confirm(idC)
        h.settle()
        h.stop()
        assertFalse(c.perform("act", data("x", 6)), "disconnected after stop")

        val d = h.subscribe(idD)
        h.connect()
        h.server.confirm(idD)
        h.settle()
        h.registry.closeAll()
        assertFalse(d.perform("act", data("x", 7)), "closed")

        h.settle()
        assertEquals(listOf(frame(idA, data("x", 3))), h.allPerforms())
    }

    @Test
    fun unencodableDataThrowsWithoutLeakingDataAndSendsNothing() = subscriptionTest(quiet) { h ->
        h.connect()
        val a = h.subscribe(idA)
        h.settle()
        h.server.confirm(idA)
        h.settle()

        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val ex = assertFailsWith<IllegalArgumentException> {
                a.perform("act", buildJsonObject {
                    put("secret", "s3cr3t")
                    put("v", bad)
                })
            }
            val message = ex.message.orEmpty()
            assertTrue(message.startsWith("unencodable_data"))
            assertFalse(message.contains("s3cr3t"))
            assertFalse(message.contains(bad.toString()))
            assertNull(ex.cause)
        }
        h.settle()
        assertTrue(h.wire().performs().isEmpty())

        assertTrue(a.perform("act", data("ok", 1)))
        val b = h.subscribe(idB)
        h.settle()
        assertEquals(listOf(frame(idA, data("ok", 1))), h.wire().performs())
        assertEquals(1, h.subscribes(idB))
        assertEquals(SubscriptionState.Pending, b.state.value)
    }

    @Test
    fun performThatReturnedFalseNeverAppearsOnTheWireAfterReconnect() = subscriptionTest(quiet) { h ->
        val a = h.subscribe(idA)
        assertFalse(a.perform("act", data("attempt", 1)))

        h.connect()
        assertFalse(a.perform("act", data("attempt", 2)))

        h.server.drop()
        h.settle()
        assertFalse(a.perform("act", data("attempt", 3)))

        h.advance(2_000)
        h.server.welcome()
        h.settle()
        h.server.confirm(idA)
        h.settle()
        assertTrue(a.perform("act", data("attempt", 4)))
        h.settle()

        assertEquals(listOf(frame(idA, data("attempt", 4))), h.allPerforms())
    }

    @Test
    fun rendezvousOutgoingTwentyConcurrentPerformsAllTrueInEnqueueOrder() = subscriptionTest(quiet) { h ->
        h.server.withRendezvousOutgoing()
        h.connect()
        val a = h.subscribe(idA)
        h.settle()
        h.server.confirm(idA)
        h.settle()

        val results = List(20) { i -> async { a.perform("act", data("seq", i)) } }.awaitAll()
        h.settle()

        assertEquals(List(20) { true }, results)
        assertEquals(List(20) { frame(idA, data("seq", it)) }, h.wire().performs())
    }

    @Test
    fun rendezvousOutgoingHundredHandlesAtWelcomePutHundredSubscribesWithoutResend() = subscriptionTest(quiet) { h ->
        h.server.withRendezvousOutgoing()
        h.start()
        val subs = (0 until 100).map { h.subscribe("""{"channel":"C","n":$it}""") }
        h.server.welcome()
        h.settle()

        assertEquals(0L, currentTime)
        val frames = h.wire()
        assertEquals(100, frames.size)
        subs.forEach { assertEquals(1, h.subscribes(it.identifier, frames)) }
    }

    @Test
    fun rendezvousPausedReaderFrameOfDroppedSessionNeverReachesNewSession() = subscriptionTest(quiet) { h ->
        h.server.withRendezvousOutgoing()
        h.connect()
        val a = h.subscribe(idA)
        h.settle()
        h.server.confirm(idA)
        h.settle()

        h.server.pauseReader()
        h.settle()
        val marker = data("marker", 1)
        assertTrue(a.perform("act", marker))
        h.settle()

        h.server.drop()
        h.settle()
        h.advance(2_000)
        h.server.welcome()
        h.settle()
        h.server.confirm(idA)
        h.settle()
        assertEquals(SubscriptionState.Subscribed(2), a.state.value)

        h.server.resumeReader()
        h.settle()

        val newWire = h.wire()
        assertEquals(2, h.server.sessions().size)
        assertEquals(1, h.subscribes(idA, newWire))
        assertTrue(newWire.none { it == frame(idA, marker) })
        assertTrue(h.allPerforms().isEmpty())
    }
}
