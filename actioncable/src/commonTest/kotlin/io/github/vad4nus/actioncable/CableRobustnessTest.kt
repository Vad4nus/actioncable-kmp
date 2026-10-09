package io.github.vad4nus.actioncable

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CableRobustnessTest {

    @Test
    fun onRequestThrowingIsAnOpenFailureWithOneWarn() = runTest {
        val requestTimes = mutableListOf<Long>()
        val h = CableTestHarness(this, onRequest = {
            requestTimes += testScheduler.currentTime
            if (requestTimes.size == 1) throw IllegalStateException("request_marker")
        })
        h.start()
        h.settle()

        assertEquals(CableState.Connecting(1), h.state.value)
        assertEquals(listOf("request_callback_failed host=fake attempt=0 class=IllegalStateException"), h.log.warns())
        assertEquals(0, h.server.calls)

        h.advance(1_250)
        assertEquals(2, requestTimes.size)
        assertTrue(requestTimes[1] in 750..1_250, "retry at ${requestTimes[1]}")
        assertEquals(1, h.server.calls)
        assertEquals(CableState.Connecting(1), h.state.value)
        assertEquals(1, h.log.warns().size)
        h.stop()
    }

    @Test
    fun throwingLoggerDoesNotBreakTheConnection() = runTest {
        val h = CableTestHarness(this, logger = { _, _ -> throw IllegalStateException("logger_marker") })
        h.start()
        h.settle()
        h.server.welcome()
        h.settle()
        assertEquals(CableState.Connected, h.state.value)

        h.server.drop()
        h.settle()
        h.advanceUntil { h.server.calls == 2 }
        assertEquals(CableState.Connecting(1), h.state.value)
        h.stop()
    }

    @Test
    fun watchdogCancellationIsALossAndTheLoopReconnects() = runTest {
        var lost = 0
        val h = CableTestHarness(
            this,
            options = CableOptions().apply { staleTimeout = 5.seconds },
            onLost = { lost++ },
        )
        h.start()
        h.settle()
        h.server.welcome()
        h.settle()

        h.advance(5_000)
        assertEquals(1, lost)
        assertEquals(1, h.server.cancellations)
        assertEquals(CableState.Connecting(1), h.state.value)
        assertEquals(listOf("session_failed host=fake attempt=0 class=StaleTimeoutException"), h.log.warns())

        h.advanceUntil { h.server.calls == 2 }
        assertTrue(h.callTimes()[1] - 5_000 in 750..1_250)
        h.server.welcome()
        h.settle()
        assertEquals(CableState.Connected, h.state.value)
        h.stop()
    }

    @Test
    fun sessionWhoseJobEndsWithoutClosingIncomingIsReplacedBeforeTheWatchdog() = runTest {
        val h = CableTestHarness(this)
        h.server.orphanNext()
        h.start()
        h.settle()

        h.advance(999)
        assertEquals(CableState.Connecting(0), h.state.value)
        h.advance(1)
        assertEquals(CableState.Connecting(1), h.state.value)
        assertEquals(listOf("handshake_failed host=fake attempt=0 class=StaleTimeoutException"), h.log.warns())

        h.advanceUntil { h.server.calls == 2 }
        assertTrue(h.callTimes()[1] - 1_000 in 750..1_250)
        h.server.welcome()
        h.settle()
        assertEquals(CableState.Connected, h.state.value)
        h.stop()
    }
}
