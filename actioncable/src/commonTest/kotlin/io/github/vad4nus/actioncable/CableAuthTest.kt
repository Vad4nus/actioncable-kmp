package io.github.vad4nus.actioncable

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class CableAuthTest {

    @Test
    fun refreshedUnauthorizedReconnectsOnceAtTheSameAttempt() = runTest {
        lateinit var h: CableTestHarness
        val seen = mutableListOf<CableState>()
        h = CableTestHarness(this, onUnauthorized = { seen += h.state.value; true })
        h.server.failNext(2)
        h.start()
        h.advanceUntil { h.server.calls == 3 }
        val t = h.now()
        h.server.disconnect("unauthorized")
        h.settle()

        assertEquals(listOf<CableState>(CableState.Connecting(2)), seen)
        assertEquals(4, h.server.calls)
        assertEquals(t, h.callTimes()[3])
        h.server.welcome()
        h.settle()
        assertEquals(CableState.Connected, h.state.value)
    }

    @Test
    fun refreshedUnauthorizedDoesNotCountAgainstMaxReconnectAttempts() = runTest {
        lateinit var h: CableTestHarness
        val seen = mutableListOf<CableState>()
        h = CableTestHarness(
            this,
            options = CableOptions().apply { maxReconnectAttempts = 0 },
            onUnauthorized = { seen += h.state.value; true },
        )
        h.start()
        h.settle()
        h.server.disconnect("unauthorized")
        h.settle()

        assertEquals(listOf<CableState>(CableState.Connecting(0)), seen)
        assertEquals(2, h.server.calls)
        h.server.welcome()
        h.settle()
        assertEquals(CableState.Connected, h.state.value)
    }

    @Test
    fun tokenExpiredWhileConnectedRefreshesAndReconnects() = runTest {
        lateinit var h: CableTestHarness
        val seen = mutableListOf<CableState>()
        h = CableTestHarness(this, onUnauthorized = { seen += h.state.value; true })
        h.start()
        h.settle()
        h.server.welcome()
        h.settle()
        h.advance(2_000)
        val t = h.now()
        h.server.disconnect("token_expired")
        h.settle()

        assertEquals(listOf<CableState>(CableState.Connecting(0)), seen)
        assertEquals(2, h.server.calls)
        assertEquals(t, h.callTimes()[1])
    }

    @Test
    fun secondUnauthorizedInARowStops() = runTest {
        var handled = 0
        val h = CableTestHarness(this, onUnauthorized = { handled++; true })
        h.start()
        h.settle()
        h.server.disconnect("unauthorized")
        h.settle()
        h.server.disconnect("unauthorized")
        h.settle()

        assertEquals(CableState.Stopped("unauthorized"), h.state.value)
        assertEquals(1, handled)
        assertEquals(2, h.server.calls)
    }

    @Test
    fun unhealthyWelcomedSessionBetweenUnauthorizedsStillStops() = runTest {
        var handled = 0
        val h = CableTestHarness(this, onUnauthorized = { handled++; true })
        h.start()
        h.settle()
        h.server.disconnect("unauthorized")
        h.settle()
        h.server.welcome()
        h.settle()
        h.advance(2_000)
        h.server.disconnect("unauthorized")
        h.settle()

        assertEquals(CableState.Stopped("unauthorized"), h.state.value)
        assertEquals(1, handled)
    }

    @Test
    fun openFailureBetweenUnauthorizedsResetsTheStreak() = runTest {
        var handled = 0
        val h = CableTestHarness(this, onUnauthorized = { handled++; true })
        h.start()
        h.settle()
        h.server.failNext(1)
        h.server.disconnect("unauthorized")
        h.settle()
        assertEquals(CableState.Connecting(1), h.state.value)
        h.advanceUntil { h.server.calls == 3 }
        h.server.disconnect("unauthorized")
        h.settle()

        assertEquals(2, handled)
        assertEquals(4, h.server.calls)
    }

    @Test
    fun gateCloseBetweenUnauthorizedsResetsTheStreak() = runTest {
        var handled = 0
        val gate = MutableStateFlow(true)
        val h = CableTestHarness(this, canConnect = gate, onUnauthorized = { handled++; true })
        h.start()
        h.settle()
        h.server.disconnect("unauthorized")
        h.settle()
        gate.value = false
        h.settle()
        gate.value = true
        h.settle()
        h.server.disconnect("unauthorized")
        h.settle()

        assertEquals(2, handled)
        assertEquals(4, h.server.calls)
    }

    @Test
    fun earlyServerRestartBetweenUnauthorizedsResetsTheStreak() = runTest {
        var handled = 0
        val h = CableTestHarness(this, onUnauthorized = { handled++; true })
        h.start()
        h.settle()
        h.server.disconnect("unauthorized")
        h.settle()
        h.server.welcome()
        h.settle()
        h.advance(2_000)
        h.server.disconnect("server_restart", reconnect = true)
        h.settle()
        h.advanceUntil { h.server.calls == 3 }
        h.server.disconnect("unauthorized")
        h.settle()

        assertEquals(2, handled)
    }

    @Test
    fun healthySessionBetweenUnauthorizedsResetsTheStreak() = runTest {
        lateinit var h: CableTestHarness
        val seen = mutableListOf<CableState>()
        h = CableTestHarness(this, onUnauthorized = { seen += h.state.value; true })
        h.server.failNext(1)
        h.start()
        h.advanceUntil { h.server.calls == 2 }
        h.server.disconnect("unauthorized")
        h.settle()
        h.server.welcome()
        h.settle()
        h.advance(12_000)
        h.server.disconnect("token_expired")
        h.settle()

        assertEquals(listOf<CableState>(CableState.Connecting(1), CableState.Connecting(0)), seen)
    }

    @Test
    fun declinedRefreshStopsAndStartRestartsAtAttemptZero() = runTest {
        val h = CableTestHarness(this, onUnauthorized = { false })
        h.server.failNext(1)
        h.start()
        h.advanceUntil { h.server.calls == 2 }
        h.server.disconnect("unauthorized")
        h.settle()
        assertEquals(CableState.Stopped("unauthorized"), h.state.value)

        h.start()
        h.settle()
        assertEquals(CableState.Connecting(0), h.state.value)
        assertEquals(3, h.server.calls)
    }

    @Test
    fun throwingHandlerIsAnOpenFailure() = runTest {
        var handled = 0
        val h = CableTestHarness(this, onUnauthorized = {
            handled++
            if (handled == 1) throw IllegalStateException("refresh")
            true
        })
        h.start()
        h.settle()
        val t = h.now()
        h.server.disconnect("unauthorized")
        h.settle()

        assertEquals(CableState.Connecting(1), h.state.value)
        assertEquals(listOf("unauthorized_callback_failed host=fake attempt=0 class=IllegalStateException"), h.log.warns())
        h.advanceUntil { h.server.calls == 2 }
        assertTrue(h.callTimes()[1] - t in 750..1_250)
        h.server.disconnect("unauthorized")
        h.settle()
        assertEquals(2, handled)
    }

    @Test
    fun timedOutHandlerIsAnOpenFailure() = runTest {
        var handled = 0
        val h = CableTestHarness(this, onUnauthorized = {
            handled++
            if (handled == 1) withTimeout(10.milliseconds) { awaitCancellation() }
            true
        })
        h.start()
        h.settle()
        h.server.disconnect("unauthorized")
        h.settle()
        h.advance(10)
        val t = h.now()

        assertEquals(CableState.Connecting(1), h.state.value)
        assertEquals(
            listOf("unauthorized_callback_failed host=fake attempt=0 class=TimeoutCancellationException"),
            h.log.warns(),
        )
        h.advanceUntil { h.server.calls == 2 }
        assertTrue(h.callTimes()[1] - t in 750..1_250)
        h.server.disconnect("unauthorized")
        h.settle()
        assertEquals(2, handled)
    }

    @Test
    fun throwingHandlerWithNoReconnectsLeftStops() = runTest {
        val h = CableTestHarness(
            this,
            options = CableOptions().apply { maxReconnectAttempts = 0 },
            onUnauthorized = { throw IllegalStateException("refresh") },
        )
        h.start()
        h.settle()
        h.server.disconnect("unauthorized")
        h.settle()

        assertEquals(CableState.Stopped("max_reconnect_attempts"), h.state.value)
        assertEquals(1, h.server.calls)
    }

    @Test
    fun gateClosingDuringTheHandlerDisconnectsWithoutStopping() = runTest {
        var handled = 0
        val gate = MutableStateFlow(true)
        val h = CableTestHarness(this, canConnect = gate, onUnauthorized = { handled++; delay(5_000); true })
        h.start()
        h.settle()
        h.server.disconnect("unauthorized")
        h.settle()
        h.advance(1_000)
        gate.value = false
        h.settle()
        assertEquals(CableState.Disconnected, h.state.value)

        gate.value = true
        h.settle()
        assertEquals(2, h.server.calls)
        h.server.disconnect("unauthorized")
        h.settle()
        assertEquals(2, handled)
        assertTrue(h.states.none { it is CableState.Stopped }, h.states.toString())
    }

    @Test
    fun stopDuringTheHandlerEndsDisconnected() = runTest {
        val h = CableTestHarness(this, onUnauthorized = {
            withContext(NonCancellable) { delay(1_000) }
            false
        })
        h.start()
        h.settle()
        h.server.disconnect("unauthorized")
        h.settle()
        val stopping = launch { h.stop() }
        h.advance(2_000)

        assertTrue(stopping.isCompleted)
        assertEquals(CableState.Disconnected, h.state.value)
        h.advance(600_000)
        assertEquals(CableState.Disconnected, h.state.value)
        assertTrue(h.states.none { it is CableState.Stopped }, h.states.toString())
        assertEquals(1, h.server.calls)
    }
}
