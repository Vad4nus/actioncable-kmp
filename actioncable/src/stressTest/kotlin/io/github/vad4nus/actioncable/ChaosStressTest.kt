package io.github.vad4nus.actioncable

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ChaosStressTest {

    @Test
    fun blackholeIsDetectedWithinStaleTimeoutAndTheSubscriptionReturnsAfterHealing() = chaosTest { chaos ->
        val client = client(url = CHAOS_CABLE_URL, options = { staleTimeout = 5.seconds })
        client.connect()
        val subscription = client.subscribe(room("blackhole"))
        subscription.awaitState(SubscriptionState.Subscribed(1))
        delay(11.seconds)

        val start = TimeSource.Monotonic.markNow()
        chaos.mode("blackhole")
        within(10.seconds, "loss detection") { client.state.first { it != CableState.Connected } }
        val detected = start.elapsedNow()
        delay(6.seconds)
        chaos.mode("pass")

        subscription.awaitState(SubscriptionState.Subscribed(2), timeout = 30.seconds)
        assertTrue(subscription.perform("echo", echo(JsonPrimitive("healed"))))
        assertEquals(JsonPrimitive("healed"), subscription.nextMessage())
        println("chaos-blackhole detected=$detected")
        assertTrue(detected in 1500.milliseconds..6500.milliseconds, "detected after $detected")
    }

    @Test
    fun aStalledUpgradeEndsWithinTheEngineTimeoutAfterDisconnect() = chaosTest { chaos ->
        val before = chaos.openLinks()
        chaos.mode("blackhole")
        val client = client(url = CHAOS_CABLE_URL)
        client.connect()
        within(5.seconds, "upgrade held by the proxy") { while (chaos.openLinks() == before) delay(100) }
        delay(2.seconds)

        val start = TimeSource.Monotonic.markNow()
        client.disconnect()
        within(75.seconds, "end of the stalled upgrade") { while (chaos.openLinks() > before) delay(100) }
        println("chaos-stalled-upgrade ended=${start.elapsedNow()}")
    }

    @Test
    fun refusedConnectionsBackOffOneTwoFourEightSixteenThirtySeconds() = chaosTest(timeout = 240.seconds) { chaos ->
        chaos.mode("refuse")
        val client = client(url = CHAOS_CABLE_URL)
        val timeline = timeline(client)
        client.connect()
        within(100.seconds, "seven refused attempts") {
            while (timeline.failedAttempts().size < 7) delay(100)
        }
        chaos.mode("pass")
        client.awaitState(CableState.Connected, timeout = 45.seconds)

        val gaps = timeline.failedAttempts().take(7).zipWithNext { a, b -> b - a }
        println("chaos-backoff gaps=$gaps")
        listOf(1, 2, 4, 8, 16, 30).map { it.seconds }.zip(gaps).forEach { (base, gap) ->
            assertTrue(gap in base * 0.75 - 50.milliseconds..base * 1.25 + 500.milliseconds, "gap $gap for $base in $gaps")
        }
    }

    @Test
    fun hundredClientsSurviveAMinuteWithoutTheServerAndSpreadTheirReconnects() = chaosTest(timeout = 360.seconds) { chaos ->
        val origin = TimeSource.Monotonic.markNow()
        val clients = List(100) { client() }
        val timelines = clients.map { timeline(it, origin) }
        val subscriptions = clients.mapIndexed { i, client ->
            client.connect()
            client.subscribe(room("herd-$i"))
        }
        subscriptions.forEach { it.awaitState(SubscriptionState.Subscribed(1), timeout = 30.seconds) }
        awaitConnections(100)
        delay(11.seconds)

        val killed = origin.elapsedNow()
        chaos.stopServer()
        delay(60.seconds)
        val restarted = origin.elapsedNow()
        chaos.startServer()
        val ready = origin.elapsedNow()
        within(60.seconds, "100 resubscriptions") {
            subscriptions.forEach { s -> s.state.first { it == SubscriptionState.Subscribed(2) } }
        }
        within(10.seconds, "100 server connections") { while (connections() != 100) delay(200) }

        val firstRetries = timelines.map { t -> t.failedAttempts().first { it > killed } - killed }
        val outageAttempts = timelines.map { t -> t.failedAttempts().count { it in killed..restarted } }
        val reconnects = timelines.map { it.lastConnected() - ready }
        println(
            "chaos-herd startup=${ready - restarted} firstRetry=${firstRetries.min()}..${firstRetries.max()} " +
                "attempts=${outageAttempts.min()}..${outageAttempts.max()} reconnect=${reconnects.min()}..${reconnects.max()}",
        )
        assertTrue(firstRetries.all { it < 3.seconds }, "first retries $firstRetries")
        assertTrue(firstRetries.max() - firstRetries.min() > 300.milliseconds, "first retries bunched: $firstRetries")
        assertTrue(outageAttempts.all { it in 5..9 }, "attempts during the outage $outageAttempts")
        assertTrue(reconnects.max() - reconnects.min() > 2.seconds, "reconnects bunched: $reconnects")
        assertTrue(reconnects.max() < 45.seconds, "slowest reconnect ${reconnects.max()}")
    }

    @Test
    fun slowLinkKeepsTheSessionAndDeliversEverything() = chaosTest(timeout = 180.seconds) { chaos ->
        chaos.mode("latency", "ms" to 800)
        val logger = CapturingLogger()
        CableLog.logger = logger
        val client = client(url = CHAOS_CABLE_URL)
        client.connect()
        val subscription = client.subscribe(room("slow"))
        subscription.awaitState(SubscriptionState.Subscribed(1), timeout = 20.seconds)

        val start = TimeSource.Monotonic.markNow()
        assertTrue(subscription.perform("echo", echo(JsonPrimitive("over a slow link"))))
        assertEquals(JsonPrimitive("over a slow link"), subscription.nextMessage())
        val roundTrip = start.elapsedNow()

        chaos.mode("throttle", "bps" to 4096)
        val payload = JsonPrimitive("t".repeat(24 * 1024))
        val sent = TimeSource.Monotonic.markNow()
        assertTrue(subscription.perform("echo", echo(payload)))
        assertEquals(payload, subscription.nextMessage(timeout = 40.seconds))
        val throttled = sent.elapsedNow()
        delay(20.seconds)

        println("chaos-slow roundTrip=$roundTrip throttled=$throttled")
        assertTrue(roundTrip >= 1600.milliseconds, "round trip $roundTrip")
        assertTrue(throttled >= 10.seconds, "24 KiB each way at 4 KiB/s took $throttled")
        assertEquals(1, logger.lines.count { "state=Connected" in it }, logger.lines.toString())
        assertEquals(CableState.Connected, client.state.value)
    }

    @Test
    fun aFrameCutMidwayIsALossAndTheClientResubscribes() = chaosTest { chaos ->
        val logger = CapturingLogger()
        CableLog.logger = logger
        val client = client(url = CHAOS_CABLE_URL)
        client.connect()
        val subscription = client.subscribe(room("cut"))
        subscription.awaitState(SubscriptionState.Subscribed(1))

        val payload = JsonPrimitive("c".repeat(16 * 1024))
        chaos.cut()
        assertTrue(subscription.perform("echo", echo(payload)))
        subscription.awaitState(SubscriptionState.Subscribed(2), timeout = 20.seconds)
        val delivered = subscription.pendingMessage()
        assertTrue(subscription.perform("echo", echo(JsonPrimitive("after the cut"))))
        assertEquals(JsonPrimitive("after the cut"), subscription.nextMessage())

        println("chaos-cut ${logger.lines.filter { it.startsWith("WARN") }}")
        assertTrue(delivered == null || delivered == payload, "partial delivery")
    }

    @Test
    fun resetConnectionsAreReplacedAndResubscribed() = chaosTest { chaos ->
        val logger = CapturingLogger()
        CableLog.logger = logger
        val subscriptions = List(10) { i ->
            val client = client(url = CHAOS_CABLE_URL)
            client.connect()
            client.subscribe(room("reset-$i"))
        }
        subscriptions.forEach { it.awaitState(SubscriptionState.Subscribed(1)) }

        chaos.drop()

        within(20.seconds, "10 resubscriptions") {
            subscriptions.forEach { s -> s.state.first { it == SubscriptionState.Subscribed(2) } }
        }
        awaitConnections(10)
        println("chaos-reset ${logger.lines.filter { it.startsWith("WARN") }}")
    }
}
