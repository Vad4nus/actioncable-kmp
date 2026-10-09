package io.github.vad4nus.actioncable

import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ImmediateRejectionStressTest {

    @Test
    fun everyImmediateRejectionStopsTheClientWithinFiveSeconds() = e2eTest(timeout = 3600.seconds) {
        val attempts = mutableMapOf<Int, Int>()
        val slow = mutableListOf<String>()
        repeat(1000) { run ->
            val logger = CapturingLogger()
            CableLog.logger = logger
            val viaHeader = run % 2 == 1
            val client = client(
                url = if (viaHeader) CABLE_URL else "$CABLE_URL?token=invalid",
                onRequest = {
                    origin()
                    if (viaHeader) header(HttpHeaders.Authorization, "Bearer invalid")
                },
            )
            val start = TimeSource.Monotonic.markNow()
            client.connect()
            client.awaitState(CableState.Stopped("unauthorized"), timeout = 60.seconds)
            val took = start.elapsedNow()
            val n = logger.lines.count { "state=Connecting" in it }
            attempts[n] = (attempts[n] ?: 0) + 1
            if (took > 5.seconds) slow += "run=$run took=$took lines=${logger.lines}"
            client.close()
        }
        println("immediate-rejection attempts per lifecycle: ${attempts.entries.sortedBy { it.key }}")

        assertEquals(emptyList(), slow)
    }
}
