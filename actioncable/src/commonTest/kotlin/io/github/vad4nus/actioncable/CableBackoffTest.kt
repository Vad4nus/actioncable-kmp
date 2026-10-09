package io.github.vad4nus.actioncable

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class CableBackoffTest {

    private val quiet get() = CableOptions().apply { staleTimeout = 10.minutes }

    private fun assertJittered(base: Long, actual: Long, label: String) =
        assertTrue(actual in base * 3 / 4..base * 5 / 4, "$label: $actual not within 25 % of $base")

    @Test
    fun consecutiveFailuresBackOffExponentiallyAndCapAt30s() = runTest {
        val h = CableTestHarness(this)
        h.server.failNext(1_000)
        h.start()
        h.settle()
        h.advanceUntil(step = 1_000) { h.server.calls == 101 }

        val gaps = h.callTimes().zipWithNext { a, b -> b - a }
        listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000).forEachIndexed { i, base ->
            assertJittered(base, gaps[i], "wait before attempt ${i + 1}")
        }
        assertJittered(30_000, gaps[99], "wait before attempt 100")
        val capped = gaps.drop(6)
        assertTrue(capped.max() - capped.min() >= 7_500, "capped waits are not spread: ${capped.min()}..${capped.max()}")
        assertEquals(CableState.Connecting(101), h.state.value)
        h.stop()
    }

    @Test
    fun welcomeFollowedByImmediateDropKeepsGrowingTheDelay() = runTest {
        val h = CableTestHarness(this, options = quiet)
        h.start()
        h.settle()

        listOf(1_000L, 2_000, 4_000, 8_000, 16_000).forEachIndexed { i, base ->
            h.server.welcome()
            h.settle()
            h.server.drop()
            h.settle()
            val dropAt = h.now()
            assertEquals(CableState.Connecting(i + 1), h.state.value)
            h.advanceUntil { h.server.calls == i + 2 }
            assertJittered(base, h.callTimes()[i + 1] - dropAt, "delay after drop ${i + 1}")
        }
        h.stop()
    }

    private fun TestScope.clientAfterTwoFailures(seed: Long): CableTestHarness {
        val h = CableTestHarness(this, options = quiet, seed = seed)
        h.server.failNext(2)
        return h
    }

    @Test
    fun dropElevenSecondsAfterWelcomeRestartsAtAttemptZero() = runTest {
        val h = clientAfterTwoFailures(seed = 7)
        h.start()
        h.settle()
        h.advanceUntil { h.server.sessions().isNotEmpty() }
        assertEquals(CableState.Connecting(2), h.state.value)
        h.server.welcome()
        h.settle()

        h.advance(11_000)
        h.server.drop()
        h.settle()
        val dropAt = h.now()
        assertEquals(CableState.Connecting(0), h.state.value)
        h.advanceUntil { h.server.calls == 4 }
        assertTrue(h.callTimes()[3] - dropAt in 0..1_000)
        h.stop()
    }

    @Test
    fun dropNineSecondsAfterWelcomeContinuesTheBackoff() = runTest {
        val h = clientAfterTwoFailures(seed = 7)
        h.start()
        h.settle()
        h.advanceUntil { h.server.sessions().isNotEmpty() }
        h.server.welcome()
        h.settle()

        h.advance(9_000)
        h.server.drop()
        h.settle()
        val dropAt = h.now()
        assertEquals(CableState.Connecting(3), h.state.value)
        h.advanceUntil { h.server.calls == 4 }
        assertJittered(4_000, h.callTimes()[3] - dropAt, "delay after an unhealthy drop")
        h.stop()
    }

    @Test
    fun healthyDropsOfTenSeededClientsSpreadInsideMinDelay() = runTest {
        val delays = (1L..10L).map { seed ->
            val h = CableTestHarness(this, options = quiet, seed = seed)
            h.start()
            h.settle()
            h.server.welcome()
            h.settle()
            h.advance(11_000)
            h.server.drop()
            h.settle()
            val dropAt = h.now()
            h.advanceUntil { h.server.calls == 2 }
            h.stop()
            h.callTimes()[1] - dropAt
        }
        assertTrue(delays.all { it in 0..1_000 }, "$delays")
        assertTrue(delays.toSet().size > 1, "$delays")
        assertTrue(delays.max() - delays.min() >= 500, "$delays")
    }

    @Test
    fun maxReconnectAttemptsThreeAllowsFourCalls() = runTest {
        val h = CableTestHarness(this, options = CableOptions().apply { maxReconnectAttempts = 3; minReconnectDelay = 100.milliseconds })
        h.server.failNext(100)
        h.start()
        h.settle()
        h.advance(600_000)

        assertEquals(CableState.Stopped("max_reconnect_attempts"), h.state.value)
        assertEquals(4, h.server.calls)
    }

    @Test
    fun maxReconnectAttemptsZeroAllowsOneCall() = runTest {
        val h = CableTestHarness(this, options = CableOptions().apply { maxReconnectAttempts = 0 })
        h.server.failNext(100)
        h.start()
        h.settle()
        h.advance(600_000)

        assertEquals(CableState.Stopped("max_reconnect_attempts"), h.state.value)
        assertEquals(1, h.server.calls)
    }

    @Test
    fun maxReconnectAttemptsTwoStopsAfterThreeUnhealthySessions() = runTest {
        val h = CableTestHarness(this, options = quiet.apply { maxReconnectAttempts = 2 })
        h.start()
        h.settle()

        repeat(3) {
            h.advanceUntil { h.server.sessions().size == it + 1 }
            h.server.welcome()
            h.settle()
            h.server.drop()
            h.settle()
        }
        h.advance(600_000)

        assertEquals(CableState.Stopped("max_reconnect_attempts"), h.state.value)
        assertEquals(3, h.server.sessions().size)
        assertEquals(3, h.server.calls)
    }
}
