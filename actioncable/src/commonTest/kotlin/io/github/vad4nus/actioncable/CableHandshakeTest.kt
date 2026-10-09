package io.github.vad4nus.actioncable

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CableHandshakeTest {

    @Test
    fun staysConnectingUntilWelcomeAndOnReadyWaitsForIt() = runTest {
        var ready = 0
        val h = CableTestHarness(this, onReady = { ready++ })
        h.start()
        h.settle()
        h.server.ping()
        h.server.confirm("""{"channel":"A"}""")
        h.settle()

        assertEquals(CableState.Connecting(0), h.state.value)
        assertEquals(0, ready)

        h.server.welcome()
        h.settle()
        assertEquals(CableState.Connected, h.state.value)
        assertEquals(1, ready)
        h.stop()
    }

    @Test
    fun noWelcomeWithinStaleTimeoutCancelsTheSession() = runTest {
        val h = CableTestHarness(this)
        h.start()
        h.settle()

        h.advance(14_999)
        assertEquals(CableState.Connecting(0), h.state.value)
        assertEquals(0, h.server.cancellations)

        h.advance(1)
        assertEquals(CableState.Connecting(1), h.state.value)
        assertEquals(1, h.server.cancellations)
        assertEquals(listOf("handshake_failed host=fake attempt=0 class=StaleTimeoutException"), h.log.warns())
        h.stop()
    }

    @Test
    fun pingsBeforeWelcomeDoNotExtendTheDeadline() = runTest {
        val h = CableTestHarness(this)
        h.start()
        h.settle()

        repeat(4) {
            h.advance(3_000)
            h.server.ping()
            h.settle()
        }
        h.advance(2_999)
        assertEquals(CableState.Connecting(0), h.state.value)

        h.advance(1)
        assertEquals(CableState.Connecting(1), h.state.value)
        assertEquals(1, h.server.cancellations)
        h.stop()
    }

    @Test
    fun afterWelcomeLastFrameAt14sDropsAt29s() = runTest {
        var lost = 0
        val h = CableTestHarness(this, onLost = { lost++ })
        h.start()
        h.settle()
        h.server.welcome()
        h.settle()

        h.advance(14_000)
        h.server.ping()
        h.settle()

        h.advance(14_999)
        assertEquals(CableState.Connected, h.state.value)
        assertEquals(0, lost)

        h.advance(1)
        assertEquals(1, lost)
        assertEquals(CableState.Connecting(0), h.state.value)
        assertEquals(2, h.server.calls)
        assertEquals(29_000, h.callTimes()[1])
        h.stop()
    }

    @Test
    fun pingsAloneKeepAWelcomedSessionAlive() = runTest {
        val h = CableTestHarness(this)
        h.start()
        h.settle()
        h.server.welcome()
        h.settle()

        repeat(40) {
            h.advance(3_000)
            h.server.ping()
            h.settle()
        }
        assertEquals(CableState.Connected, h.state.value)
        assertEquals(1, h.server.calls)
        assertFalse(h.states.any { it is CableState.Connecting && it.attempt > 0 })
        assertTrue(h.log.warns().isEmpty())
        h.stop()
    }
}
