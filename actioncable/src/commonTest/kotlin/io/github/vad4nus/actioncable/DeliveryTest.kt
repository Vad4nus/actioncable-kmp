package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.subscription.CableSubscriptionImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeliveryTest {

    private val idA = """{"channel":"A"}"""
    private val idB = """{"channel":"B"}"""

    private fun msg(n: Int) = buildJsonObject {
        put("n", n)
        put("p", "payload-marker")
    }

    private fun JsonElement.n() = jsonObject.getValue("n").jsonPrimitive.int

    private suspend fun SubscriptionTestHarness.confirmed(identifier: String): CableSubscriptionImpl {
        val sub = subscribe(identifier)
        settle()
        server.confirm(identifier)
        settle()
        return sub
    }

    @Test
    fun overflowEpisodeEndsWithOneWarnAfter128CleanDeliveries() = subscriptionTest { h ->
        h.connect()
        val sub = h.confirmed(idA)

        repeat(200) { h.server.broadcast(idA, msg(it + 1)) }
        h.settle()
        assertTrue(h.log.warns().isEmpty())

        val received = mutableListOf<Int>()
        backgroundScope.launch { sub.messages.collect { received += it.n() } }
        h.settle()
        assertEquals((73..200).toList(), received)

        for (k in 1..128) {
            h.server.broadcast(idA, msg(200 + k))
            h.settle()
            assertEquals(200 + k, received.last())
            if (k < 128) assertTrue(h.log.warns().isEmpty(), "WARN before the 128th clean delivery, at $k")
        }
        assertEquals(listOf("buffer_overflow sub=#1 dropped=72"), h.log.warns())
        assertTrue(h.log.entries.none { it.message.contains("payload-marker") })
    }

    @Test
    fun tenThousandBroadcastsWithoutCollectorLog77WarnsAndControlFramesStillWork() = subscriptionTest { h ->
        h.connect()
        val a = h.confirmed(idA)
        val b = h.subscribe(idB)
        h.settle()

        repeat(10_000) { h.server.broadcast(idA, msg(it)) }
        h.settle()
        val warns = h.log.warns()
        assertEquals(77, warns.size)
        assertTrue(warns.all { it == "buffer_overflow sub=#1 dropped=128" })

        h.server.confirm(idB)
        h.server.reject(idA)
        h.settle()
        assertEquals(SubscriptionState.Subscribed(1), b.state.value)
        assertEquals(SubscriptionState.Rejected, a.state.value)

        h.server.disconnect("server_restart", reconnect = true)
        h.settle()
        assertEquals(SubscriptionState.Pending, a.state.value)
        assertEquals(SubscriptionState.Pending, b.state.value)
        h.advance(2_000)
        assertEquals(2, h.server.calls)
    }

    @Test
    fun secondConcurrentCollectorThrowsAndSequentialRecollectionResumesFromBuffer() = subscriptionTest { h ->
        h.connect()
        val sub = h.confirmed(idA)
        repeat(3) { h.server.broadcast(idA, msg(it + 1)) }
        h.settle()

        val first = mutableListOf<Int>()
        val firstJob = backgroundScope.launch { sub.messages.collect { first += it.n() } }
        h.settle()
        assertEquals(listOf(1, 2, 3), first)

        val ex = assertFailsWith<IllegalStateException> { sub.messages.collect { } }
        assertTrue(ex.message.orEmpty().startsWith("concurrent_collection"))

        firstJob.cancelAndJoin()
        repeat(3) { h.server.broadcast(idA, msg(it + 4)) }
        h.settle()

        val second = mutableListOf<Int>()
        backgroundScope.launch { sub.messages.collect { second += it.n() } }
        h.settle()
        assertEquals(listOf(4, 5, 6), second)
    }

    @Test
    fun unsubscribeWithFullBufferDeliversItToActiveCollectorThenCompletes() =
        subscriptionTest(CableOptions().apply { messageBufferSize = 16 }) { h ->
            h.connect()
            val sub = h.confirmed(idA)

            val gate = CompletableDeferred<Unit>()
            val received = mutableListOf<Int>()
            var completed = false
            var failure: Throwable? = null
            backgroundScope.launch {
                runCatching {
                    sub.messages.collect {
                        received += it.n()
                        gate.await()
                    }
                }.onSuccess { completed = true }.onFailure { failure = it }
            }
            h.server.broadcast(idA, msg(1))
            h.settle()
            assertEquals(listOf(1), received)

            (2..20).forEach { h.server.broadcast(idA, msg(it)) }
            h.settle()

            sub.unsubscribe()
            h.settle()
            assertEquals(SubscriptionState.Unsubscribed, sub.state.value)
            assertTrue(h.log.warns().isEmpty())
            assertTrue(h.log.entries.any { it.message == "buffer_overflow_open sub=#1 dropped=3" })

            gate.complete(Unit)
            h.settle()
            assertEquals(listOf(1) + (5..20).toList(), received)
            assertTrue(completed)
            assertNull(failure)
        }

    @Test
    fun concurrentReaderOnDefaultDispatcherDropsOnlyWhenBufferIsFull() = runTest {
        val total = 20_000
        val handle = CableSubscriptionImpl(
            identifier = idA,
            messageBufferSize = 2,
            serialNumber = 1,
            send = { false },
            removeFromRegistry = { },
        )
        val published = MutableStateFlow(0)
        val received = mutableListOf<Int>()
        var violation: String? = null

        withContext(Dispatchers.Default) {
            val collector = launch {
                var previous = 0
                handle.messages.collect { element ->
                    val r = element.jsonPrimitive.int
                    val seen = published.value
                    if (violation == null) {
                        if (r <= previous) violation = "received $r after $previous"
                        else if (r > previous + 1 && seen < r + 1) violation = "gap before $r with published=$seen"
                    }
                    previous = r
                    received += r
                }
            }
            launch {
                for (n in 1..total) {
                    published.value = n
                    handle.deliver(JsonPrimitive(n))
                }
                handle.markUnsubscribed()
            }.join()
            collector.join()
        }

        assertNull(violation)
        assertEquals(total.toLong(), received.size + handle.lost.value)
        assertEquals(listOf(total - 1, total), received.takeLast(2))
    }
}
