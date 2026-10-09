package io.github.vad4nus.actioncable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val WARMUP = 100
private const val CYCLES = 500

class LeakStressTest {

    @Test
    fun fiveHundredLifecyclesReleaseClientsThreadsAndServerConnections() = e2eTest(timeout = 900.seconds) {
        val refs = mutableListOf<() -> Any?>()
        var warm = 0
        var peak = 0
        repeat(CYCLES) { cycle ->
            refs += lifecycle { released ->
                ActionCableClient(url = CABLE_URL, onRequest = { origin() }, httpClientConfig = { install(releaseHook(released)) })
            }
            if (cycle + 1 == WARMUP) warm = liveThreads()
            if (cycle + 1 > WARMUP && cycle % 25 == 0) peak = maxOf(peak, liveThreads())
        }
        awaitConnections(0)
        val alive = survivors(refs)
        val settled = settledThreads(ceiling = warm + 8)
        println("lifecycle-leak cycles=$CYCLES warmThreads=$warm peakThreads=$peak settledThreads=$settled survivors=$alive")

        assertEquals(0, alive)
        assertTrue(peak <= warm + 32, "peak=$peak warm=$warm")
        assertTrue(settled <= warm + 8, "settled=$settled warm=$warm")
    }
}
