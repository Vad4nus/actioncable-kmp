package io.github.vad4nus.actioncable

import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.parameter
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ServerPathsE2ETest {

    private val run = Random.nextLong().toULong()

    @Test
    fun remoteDisconnectReconnectsOrStopsAsTheServerAsks() = e2eTest {
        val back = client(url = "$CABLE_URL?client_id=back-$run")
        val gone = client(url = "$CABLE_URL?client_id=gone-$run")
        back.connect()
        gone.connect()
        val subscription = back.subscribe(room("remote-$run"))
        subscription.awaitState(SubscriptionState.Subscribed(1))
        gone.awaitState(CableState.Connected)

        disconnectRemotely("back-$run", reconnect = true)
        disconnectRemotely("gone-$run", reconnect = false)

        subscription.awaitState(SubscriptionState.Subscribed(2))
        gone.awaitState(CableState.Stopped("remote"))
    }

    @Test
    fun tokenExpiredThenUnauthorizedInOneStreakRefreshOnceThenStop() = e2eTest {
        val refreshes = MutableStateFlow(0)
        val client = client(onUnauthorized = {
            refreshes.update { it + 1 }
            true
        })
        client.connect()
        val subscription = client.subscribe(room("kick-$run"))
        subscription.awaitState(SubscriptionState.Subscribed(1))

        assertTrue(subscription.perform("kick", buildJsonObject { put("reason", "token_expired") }))
        subscription.awaitState(SubscriptionState.Subscribed(2))
        assertEquals(1, refreshes.value)

        assertTrue(subscription.perform("kick", buildJsonObject { put("reason", "unauthorized") }))
        client.awaitState(CableState.Stopped("unauthorized"))
        assertEquals(1, refreshes.value)
    }

    @Test
    fun slowSubscribedIsConfirmedOnceAndDeliversOnceDespiteTheResend() = e2eTest {
        val client = client()
        client.connect()
        val subscription = client.subscribe(JsonObject(room("slow-$run") + ("slow" to JsonPrimitive(7))))
        subscription.awaitState(SubscriptionState.Subscribed(1), timeout = 20.seconds)

        assertTrue(subscription.perform("echo", echo(JsonPrimitive("once"))))
        assertEquals(JsonPrimitive("once"), subscription.nextMessage())
        delay(1.seconds)
        assertNull(subscription.pendingMessage())
        assertEquals(SubscriptionState.Subscribed(1), subscription.state.value)
        assertEquals(CableState.Connected, client.state.value)
    }

    @Test
    fun cookiesFromAHeaderOrHttpCookiesReachTheServer() = e2eTest {
        val viaHeader = client(onRequest = {
            origin()
            headers[HttpHeaders.Cookie] = "token=invalid"
        })
        val storage = AcceptAllCookiesStorage()
        storage.addCookie(Url("http://localhost:3000/"), Cookie("token", "invalid"))
        val viaPlugin = client(httpConfig = { install(HttpCookies) { this.storage = storage } })
        viaHeader.connect()
        viaPlugin.connect()

        viaHeader.awaitState(CableState.Stopped("unauthorized"))
        viaPlugin.awaitState(CableState.Stopped("unauthorized"))
    }

    @Test
    fun aTwentyThousandMessageBurstKeepsTheNewestAndWarnsPerBufferOfDrops() = e2eTest {
        val logger = CapturingLogger()
        CableLog.logger = logger
        val client = client()
        client.connect()
        val flood = client.subscribe(room("flood-$run"))
        val done = client.subscribe(room("flood-done-$run"))
        flood.awaitState(SubscriptionState.Subscribed(1))
        done.awaitState(SubscriptionState.Subscribed(1))

        assertTrue(
            flood.perform("flood", buildJsonObject {
                put("count", 20_000)
                put("done", "flood-done-$run")
            }),
        )
        assertEquals(JsonPrimitive("done"), done.nextMessage(timeout = 40.seconds))

        assertEquals((19_872 until 20_000).map { JsonPrimitive(it) }, flood.messages.take(128).toList())
        assertNull(flood.pendingMessage())
        assertEquals(155, logger.lines.count { it == "WARN buffer_overflow sub=#1 dropped=128" })
        assertEquals(155, logger.lines.count { it.startsWith("WARN") })
        assertTrue(flood.perform("echo", echo(JsonPrimitive("still here"))))
        assertEquals(JsonPrimitive("still here"), flood.nextMessage())
    }

    @Test
    fun unicodeEscapesAndControlCharactersSurviveIdentifiersAndPayloads() = e2eTest {
        val client = client()
        client.connect()
        val subscription = client.subscribe(room("\"q\" \\ / é 中 😀 \u0000   $run"))
        subscription.awaitState(SubscriptionState.Subscribed(1))
        val payloads: List<JsonElement> = listOf(
            JsonPrimitive("\u0000\u0001\u001f\u007f\u0080\u009f"),
            JsonPrimitive("😀🏳️‍🌈 שלום ‮"),
            JsonPrimitive("\"\\/\b\u000c\n\r\t</script><!-- & <"),
            JsonPrimitive("﻿�￿  "),
            JsonPrimitive("😀".repeat(50_000)),
            buildJsonObject {
                put("k\"e\\y\u0000", "v")
                put("n", Long.MAX_VALUE)
                put("t", true)
            },
        )
        payloads.forEach { payload ->
            assertTrue(subscription.perform("echo", echo(payload)))
            assertEquals(payload, subscription.nextMessage())
        }
    }

    @Test
    fun resubscribingWithAFreshNonceAlwaysLeavesALiveServerSubscription() = e2eTest {
        val client = client()
        client.connect()
        repeat(20) { n ->
            val subscription = client.subscribe(JsonObject(room("nonce-$run") + ("nonce" to JsonPrimitive(n))))
            subscription.awaitState(SubscriptionState.Subscribed(1))
            assertTrue(subscription.perform("echo", echo(JsonPrimitive(n))))
            assertEquals(JsonPrimitive(n), subscription.nextMessage())
            subscription.unsubscribe()
        }
    }

    @Test
    fun onRequestBuildsEveryAttemptSoOneTimeTicketsSurviveAReconnect() = e2eTest {
        val tickets = MutableStateFlow(0)
        val fresh = client(onRequest = {
            origin()
            parameter("nonce", "fresh-$run-${tickets.updateAndGet { it + 1 }}")
        })
        val replayed = client(onRequest = {
            origin()
            parameter("nonce", "replayed-$run")
        })
        fresh.connect()
        replayed.connect()
        val subscription = fresh.subscribe(room("ticket-$run"))
        subscription.awaitState(SubscriptionState.Subscribed(1))
        replayed.awaitState(CableState.Connected)

        restart()

        subscription.awaitState(SubscriptionState.Subscribed(2))
        replayed.awaitState(CableState.Stopped("unauthorized"))
        assertTrue(tickets.value >= 2, "tickets ${tickets.value}")
    }

    @Test
    fun refusedAndUnresolvableHostsFailEachAttemptAndStopAtTheLimit() = e2eTest {
        val options: CableOptions.() -> Unit = {
            maxReconnectAttempts = 2
            minReconnectDelay = 100.milliseconds
        }
        val logger = CapturingLogger()
        CableLog.logger = logger
        val refused = client(url = "ws://localhost:1/cable", options = options)
        val unknown = client(url = "ws://cable.invalid/cable", options = options)
        refused.connect()
        unknown.connect()

        refused.awaitState(CableState.Stopped("max_reconnect_attempts"), timeout = 30.seconds)
        unknown.awaitState(CableState.Stopped("max_reconnect_attempts"), timeout = 30.seconds)
        println("refused and unresolvable ${logger.lines.filter { it.startsWith("WARN") }}")
        assertEquals(3, logger.lines.count { it.startsWith("WARN handshake_failed host=localhost:1 ") })
        assertEquals(3, logger.lines.count { it.startsWith("WARN handshake_failed host=cable.invalid ") })
    }

    @Test
    fun aRedirectToAnotherHostNeverCarriesTheCredentials() = e2eTest {
        val logger = CapturingLogger()
        CableLog.logger = logger
        val target = "http://127.0.0.1:3000/control/sink?leak=$run"
        val client = client(
            url = "ws://localhost:3000/control/redirect?to=${target.encodeURLParameter()}",
            onRequest = {
                origin()
                headers[HttpHeaders.Authorization] = "Bearer secret-$run"
                headers[HttpHeaders.Cookie] = "session=secret-$run"
            },
            options = {
                maxReconnectAttempts = 1
                minReconnectDelay = 100.milliseconds
            },
        )
        client.connect()

        client.awaitState(CableState.Stopped("max_reconnect_attempts"))
        val sunk = sunk()
        println("redirect sink=$sunk ${logger.lines.filter { it.startsWith("WARN") }}")
        assertFalse("secret-$run" in sunk, sunk)
    }
}
