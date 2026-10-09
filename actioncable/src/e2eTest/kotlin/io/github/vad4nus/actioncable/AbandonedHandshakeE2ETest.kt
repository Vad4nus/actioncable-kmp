package io.github.vad4nus.actioncable

import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class AbandonedHandshakeE2ETest {

    @Test
    fun closingDuringTheHandshakeLeavesNoServerConnectionOrBlockedThread() = e2eTest(timeout = 120.seconds) {
        val random = Random(2)
        repeat(600) {
            val client = client()
            client.connect()
            delay(random.nextLong(0, 20))
            client.close()
        }
        awaitConnections(0)
        assertEquals(0, blockedEngineThreads())
    }
}
