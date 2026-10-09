package io.github.vad4nus.actioncable

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class HandshakeE2ETest {

    @Test
    fun untypedFactoryRunsTheHttpClientConfig() = e2eTest {
        var configRan = false
        val released = released()
        val client = track(
            ActionCableClient(
                url = CABLE_URL,
                onRequest = { origin() },
                httpClientConfig = {
                    install(WebSockets) { configRan = true }
                    install(releaseHook(released))
                },
            ),
        )
        client.connect()
        client.awaitState(CableState.Connected)

        assertTrue(configRan)
    }

    @Test
    fun typedFactoryRunsTheEngineBlock() = e2eTest {
        var engineRan = false
        val released = released()
        val client = track(
            ActionCableClient(
                url = CABLE_URL,
                engine = e2eEngine(),
                onRequest = { origin() },
                httpClientConfig = {
                    engine { engineRan = true }
                    install(releaseHook(released))
                },
            ),
        )
        client.connect()
        client.awaitState(CableState.Connected)

        assertTrue(engineRan)
    }

    @Test
    fun missingOriginKeepsRetryingAndLogsNeitherSentinel() = e2eTest {
        val probe = "probe-sntl-7c1q"
        val secret = "auth-sntl-9d4q"
        val logger = CapturingLogger()
        CableLog.logger = logger
        val client = client(
            url = "$CABLE_URL?probe=$probe",
            onRequest = { header(HttpHeaders.Authorization, "Bearer $secret") },
            options = { minReconnectDelay = 200.milliseconds },
        )
        val states = MutableStateFlow(emptyList<CableState>())
        val watcher = launch { client.state.collect { state -> states.update { it + state } } }
        client.connect()
        within(10.seconds, "Connecting(2)") { client.state.first { it is CableState.Connecting && it.attempt >= 2 } }
        watcher.cancel()

        assertTrue(states.value.none { it == CableState.Connected || it is CableState.Stopped }, states.value.toString())
        val failures = logger.lines.filter { it.startsWith("WARN handshake_failed ") }
        assertTrue(failures.isNotEmpty() && failures.all { " class=" in it }, logger.lines.toString())
        assertTrue(logger.lines.none { probe in it || secret in it }, logger.lines.toString())
    }

    @Test
    fun delayedWelcomeLeavesNoLiveSocketAfterStaleTimeoutOrDisconnect() = e2eTest {
        val start = TimeSource.Monotonic.markNow()
        val stale = client(url = "$CABLE_URL?delay=5", options = { staleTimeout = 2.seconds })
        val leaving = client(url = "$CABLE_URL?delay=5")
        stale.connect()
        leaving.connect()
        delay(1.seconds - start.elapsedNow())
        leaving.disconnect()
        delay(7.seconds - start.elapsedNow())

        assertEquals(0, connections())
    }

    @Test
    fun delayedUpgradeDisconnectsAtOnceAndLeavesNoLiveSocket() = e2eTest {
        val start = TimeSource.Monotonic.markNow()
        val client = client(url = "$CABLE_URL?delay_upgrade=5")
        client.connect()
        delay(1.seconds - start.elapsedNow())
        client.disconnect()
        client.awaitState(CableState.Disconnected, timeout = 500.milliseconds)
        delay(7.seconds - start.elapsedNow())

        assertEquals(0, connections())
    }
}
