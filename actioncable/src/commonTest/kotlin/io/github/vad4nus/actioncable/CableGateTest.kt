package io.github.vad4nus.actioncable

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CableGateTest {

    @Test
    fun closedGateEndsTheSessionAndOpeningItConnectsAtOnce() = runTest {
        val gate = MutableStateFlow(true)
        val h = CableTestHarness(this, canConnect = gate)
        h.start()
        h.settle()
        h.server.welcome()
        h.settle()
        assertEquals(CableState.Connected, h.state.value)

        gate.value = false
        h.settle()
        assertEquals(CableState.Disconnected, h.state.value)
        assertEquals(1, h.server.cancellations)

        h.advance(600_000)
        assertEquals(1, h.server.calls)

        gate.value = true
        h.settle()
        assertEquals(CableState.Connecting(0), h.state.value)
        assertEquals(2, h.server.calls)
        assertEquals(h.now(), h.callTimes()[1])
    }

    @Test
    fun closingTheGateDuringAnOpenCancelsIt() = runTest {
        val gate = MutableStateFlow(true)
        val h = CableTestHarness(this, canConnect = gate)
        h.server.hangNext()
        h.start()
        h.settle()

        gate.value = false
        h.settle()

        assertEquals(CableState.Disconnected, h.state.value)
        assertEquals(1, h.server.cancellations)
    }

    @Test
    fun flappingGateKeepsIncreasingTheAttempt() = runTest {
        val gate = MutableStateFlow(true)
        val h = CableTestHarness(this, canConnect = gate)
        h.server.failNext(1_000)
        h.start()
        h.settle()

        repeat(20) {
            gate.value = false
            h.settle()
            gate.value = true
            h.settle()
        }

        assertEquals(CableState.Connecting(21), h.state.value)
        assertEquals(21, h.server.calls)
        val attempts = h.states.filterIsInstance<CableState.Connecting>().map { it.attempt }
        assertEquals(attempts.sorted(), attempts)
    }

    @Test
    fun neverEmittingGateWaitsDisconnectedAndLogsOnce() = runTest {
        val h = CableTestHarness(this, canConnect = MutableSharedFlow())
        h.start()
        h.settle()
        h.advance(60_000)

        assertEquals(CableState.Disconnected, h.state.value)
        assertEquals(0, h.server.calls)
        assertEquals(
            listOf("gate_waiting host=fake"),
            h.log.entries.filter { it.level == CableLogger.Level.INFO }.map { it.message },
        )
    }

    @Test
    fun completedGateFlowKeepsTheClientConnected() = runTest {
        val h = CableTestHarness(this, canConnect = flowOf(true))
        h.start()
        h.settle()
        h.server.welcome()
        h.settle()
        repeat(20) {
            h.advance(3_000)
            h.server.ping()
            h.settle()
        }

        assertEquals(CableState.Connected, h.state.value)
        assertEquals(1, h.server.calls)
    }

    @Test
    fun gateFlowFailingAfterTrueKeepsTheClientConnectedAndLogsTheError() = runTest {
        val h = CableTestHarness(this, canConnect = flow { emit(true); throw IllegalStateException("gate") })
        h.start()
        h.settle()
        h.server.welcome()
        h.settle()
        repeat(20) {
            h.advance(3_000)
            h.server.ping()
            h.settle()
        }

        assertEquals(CableState.Connected, h.state.value)
        assertEquals(1, h.server.calls)
        val errors = h.log.errors()
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.all { it == "gate_failed host=fake class=IllegalStateException" }, errors.toString())
    }

    @Test
    fun gateFailingBeforeItsFirstValueIsCollectedAgainAfterBackoff() = runTest {
        val collections = mutableListOf<Long>()
        val gate = flow {
            collections += testScheduler.currentTime
            if (collections.size == 1) throw IllegalStateException("gate")
            emit(true)
        }
        val h = CableTestHarness(this, canConnect = gate)
        h.start()
        h.settle()
        assertEquals(CableState.Disconnected, h.state.value)
        assertEquals(0, h.server.calls)

        h.advanceUntil { collections.size == 2 }
        assertTrue(collections[1] - collections[0] in 750..1_250, collections.toString())
        assertEquals(CableState.Connecting(0), h.state.value)
        assertEquals(1, h.server.calls)

        h.server.welcome()
        h.settle()
        assertEquals(CableState.Connected, h.state.value)
        assertEquals(listOf("gate_failed host=fake class=IllegalStateException"), h.log.errors())
    }
}
