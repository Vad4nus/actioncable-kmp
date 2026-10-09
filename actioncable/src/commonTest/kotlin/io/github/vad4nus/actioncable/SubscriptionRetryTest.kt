package io.github.vad4nus.actioncable

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val STEP_MS = 100L
private const val PING_EVERY_MS = 3_000L

class SubscriptionRetryTest {

    private val idA = """{"channel":"A"}"""

    private fun idOf(n: Int) = """{"channel":"C","n":$n}"""

    private fun TestScope.subscribeSendTimes(h: SubscriptionTestHarness, identifier: String, untilMs: Long): List<Long> {
        var seen = h.subscribes(identifier)
        val times = MutableList(seen) { currentTime }
        while (currentTime < untilMs) {
            h.advance(STEP_MS)
            if (currentTime % PING_EVERY_MS == 0L) {
                h.server.ping()
                h.settle()
            }
            val count = h.subscribes(identifier)
            repeat(count - seen) { times += currentTime }
            seen = count
        }
        return times
    }

    private fun assertIntervals(times: List<Long>, nominal: List<Long>) {
        assertTrue(times.size > nominal.size, "send times: $times")
        times.zipWithNext { a, b -> b - a }.zip(nominal).forEachIndexed { i, (actual, n) ->
            val range = (n * 3 / 4 - STEP_MS)..(n * 5 / 4 + STEP_MS)
            assertTrue(actual in range, "re-send ${i + 1} after $actual ms, expected $n ms ±25%")
        }
    }

    @Test
    fun resendsAt5_10_20_30_30sWithDefaultsWhileServerOnlyPings() = subscriptionTest { h ->
        h.connect()
        val sub = h.subscribe(idA)
        h.settle()

        val times = subscribeSendTimes(h, idA, untilMs = 125_000)

        assertIntervals(times, listOf(5_000, 10_000, 20_000, 30_000, 30_000))
        assertEquals(0, h.unsubscribes(idA))
        assertEquals(SubscriptionState.Pending, sub.state.value)
        assertEquals(CableState.Connected, h.state.value)
        assertEquals(1, h.server.calls)
    }

    @Test
    fun maxReconnectDelay60sResendsAt5_10_20_40_60s() =
        subscriptionTest(CableOptions().apply { maxReconnectDelay = 60.seconds }) { h ->
            h.connect()
            h.subscribe(idA)
            h.settle()

            val times = subscribeSendTimes(h, idA, untilMs = 175_000)

            assertIntervals(times, listOf(5_000, 10_000, 20_000, 40_000, 60_000))
            assertEquals(1, h.server.calls)
        }

    @Test
    fun maxReconnectDelay2sResendsEvery5s() =
        subscriptionTest(CableOptions().apply { maxReconnectDelay = 2.seconds }) { h ->
            h.connect()
            h.subscribe(idA)
            h.settle()

            val times = subscribeSendTimes(h, idA, untilMs = 40_000)

            assertIntervals(times, List(5) { 5_000L })
            val intervals = times.zipWithNext { a, b -> b - a }
            assertTrue(intervals.max() - intervals.min() >= 500, "re-sends are not spread: $intervals")
            assertEquals(1, h.server.calls)
        }

    @Test
    fun hundredHandlesConfirmedAfter3sSendExactly100SubscribesInFirst5s() = subscriptionTest { h ->
        h.connect()
        val subs = (0 until 100).map { h.subscribe(idOf(it)) }
        h.settle()

        h.advance(3_000)
        subs.forEach { h.server.confirm(it.identifier) }
        h.settle()
        subs.forEach { assertEquals(SubscriptionState.Subscribed(1), it.state.value) }

        h.advance(2_000)
        val frames = h.wire()
        assertEquals(100, frames.size)
        subs.forEach { assertEquals(1, h.subscribes(it.identifier, frames)) }

        h.advance(5_000)
        assertEquals(100, h.wire().size)
    }

    @Test
    fun lateConfirmAfterResendSubscribesAndCancelsFurtherResends() =
        subscriptionTest(CableOptions().apply { staleTimeout = 10.minutes }) { h ->
            h.connect()
            val sub = h.subscribe(idA)
            h.settle()

            h.advance(6_300)
            assertEquals(2, h.subscribes(idA))

            h.server.confirm(idA)
            h.settle()
            assertEquals(SubscriptionState.Subscribed(1), sub.state.value)
            assertEquals(true, sub.loopJob?.isCompleted)

            h.advance(120_000)
            assertEquals(2, h.subscribes(idA))
        }
}
