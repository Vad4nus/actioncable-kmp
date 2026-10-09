package io.github.vad4nus.actioncable

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CableStopTest {

    @Test
    fun invalidRequestStopsForGood() = runTest {
        val h = CableTestHarness(this)
        h.start()
        h.settle()
        h.server.welcome()
        h.settle()
        h.server.disconnect("invalid_request", reconnect = false)
        h.settle()
        assertEquals(CableState.Stopped("invalid_request"), h.state.value)

        h.advance(600_000)
        assertEquals(1, h.server.calls)
        assertEquals(CableState.Stopped("invalid_request"), h.state.value)
    }

    @Test
    fun bareDisconnectStopsWithNoReason() = runTest {
        val h = CableTestHarness(this)
        h.start()
        h.settle()
        h.server.welcome()
        h.settle()
        h.server.lastSession().serverSend("""{"type":"disconnect"}""")
        h.settle()

        assertEquals(CableState.Stopped(null), h.state.value)
    }

    @Test
    fun earlyServerRestartBacksOffAtTheNextAttempt() = runTest {
        val h = CableTestHarness(this)
        h.server.failNext(1)
        h.start()
        h.advanceUntil { h.server.calls == 2 }
        h.server.welcome()
        h.settle()
        h.advance(2_000)
        val t = h.now()
        h.server.disconnect("server_restart", reconnect = true)
        h.settle()

        assertEquals(CableState.Connecting(2), h.state.value)
        h.advanceUntil { h.server.calls == 3 }
        assertTrue(h.callTimes()[2] - t in 1_500..2_500)
    }

    @Test
    fun lateServerRestartReconnectsAtAttemptZeroWithinTheMinimumDelay() = runTest {
        val h = CableTestHarness(this)
        h.server.failNext(1)
        h.start()
        h.advanceUntil { h.server.calls == 2 }
        h.server.welcome()
        h.settle()
        h.advance(12_000)
        val t = h.now()
        h.server.disconnect("server_restart", reconnect = true)
        h.settle()

        assertEquals(CableState.Connecting(0), h.state.value)
        h.advanceUntil { h.server.calls == 3 }
        assertTrue(h.callTimes()[2] - t in 0..1_000)
    }

    @Test
    fun gateToggleInStoppedDoesNothing() = runTest {
        var handled = 0
        val gate = MutableStateFlow(true)
        val h = CableTestHarness(this, canConnect = gate, onUnauthorized = { handled++; true })
        h.start()
        h.settle()
        h.server.disconnect("invalid_request")
        h.settle()
        gate.value = false
        h.settle()
        gate.value = true
        h.settle()
        h.advance(600_000)

        assertEquals(CableState.Stopped("invalid_request"), h.state.value)
        assertEquals(1, h.server.calls)
        assertEquals(0, handled)
    }

    @Test
    fun startFromStoppedWithAClosedGateWaitsForIt() = runTest {
        val gate = MutableStateFlow(true)
        val h = CableTestHarness(this, canConnect = gate)
        h.start()
        h.settle()
        h.server.disconnect("invalid_request")
        h.settle()
        gate.value = false
        h.settle()

        h.start()
        h.settle()
        assertEquals(CableState.Disconnected, h.state.value)
        assertEquals(1, h.server.calls)

        gate.value = true
        h.settle()
        assertEquals(CableState.Connecting(0), h.state.value)
        assertEquals(2, h.server.calls)
    }
}
