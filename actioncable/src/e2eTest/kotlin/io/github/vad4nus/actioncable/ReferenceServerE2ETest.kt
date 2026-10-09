package io.github.vad4nus.actioncable

import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ReferenceServerE2ETest {

    @Test
    fun anonymousQueryTokenAndHeaderTokenClientsConnectAndCloseIndependently() = e2eTest {
        val anonymous = client()
        val queryToken = client(url = "$CABLE_URL?token=valid")
        val headerToken = client(onRequest = {
            origin()
            header(HttpHeaders.Authorization, "Bearer valid")
        })
        val all = listOf(anonymous, queryToken, headerToken)
        all.forEach { it.connect() }
        all.forEach { it.awaitState(CableState.Connected) }
        awaitConnections(3)

        val left = MutableStateFlow(emptyList<CableState>())
        val watchers = listOf(queryToken, headerToken).map { client ->
            launch { client.state.collect { state -> if (state != CableState.Connected) left.update { it + state } } }
        }
        anonymous.close()
        awaitConnections(2)
        watchers.forEach { it.cancel() }

        assertEquals(emptyList(), left.value)
    }

    @Test
    fun echoedPayloadsArriveUnchangedOnlyOnTheirOwnRoom() = e2eTest {
        val client = client()
        client.connect()
        val a = client.subscribe(room("a"))
        val b = client.subscribe(room("b"))
        a.awaitState(SubscriptionState.Subscribed(1))
        b.awaitState(SubscriptionState.Subscribed(1))

        val payloads = listOf(
            buildJsonObject {
                put("text", "<tag> & \"quotes\" ✓")
                put("list", buildJsonArray { add(1); add(2.5); add(JsonNull); add(true) })
            },
            buildJsonArray { add("x"); add(buildJsonObject { put("n", -7) }) },
            JsonPrimitive("plain string"),
            JsonPrimitive(42),
            JsonPrimitive("x".repeat(largeEchoBytes)),
        )
        for (payload in payloads) {
            assertTrue(a.perform("echo", echo(payload)))
            assertEquals(payload, a.nextMessage())
        }
        assertTrue(b.perform("echo", echo(JsonPrimitive("for b"))))
        assertEquals(JsonPrimitive("for b"), b.nextMessage())

        delay(1.seconds)
        assertNull(a.pendingMessage())
        assertNull(b.pendingMessage())
    }

    @Test
    fun invalidTokenCallsOnUnauthorizedExactlyOnce() = e2eTest(timeout = 300.seconds) {
        for (viaHeader in listOf(false, true)) {
            for (refresh in listOf(true, false)) {
                repeat(unauthorizedRuns) { run ->
                    val token = MutableStateFlow("invalid")
                    val calls = MutableStateFlow(0)
                    val client = client(
                        onRequest = {
                            origin()
                            if (viaHeader) header(HttpHeaders.Authorization, "Bearer ${token.value}") else parameter("token", token.value)
                        },
                        onUnauthorized = {
                            calls.update { it + 1 }
                            if (refresh) token.value = "valid"
                            refresh
                        },
                    )
                    client.connect()
                    client.awaitState(if (refresh) CableState.Connected else CableState.Stopped("unauthorized"))
                    assertEquals(1, calls.value, "viaHeader=$viaHeader refresh=$refresh run=$run")
                    client.close()
                }
            }
        }
    }

    @Test
    fun rejectParamRejectsAndServerRestartResubscribes() = e2eTest {
        val client = client()
        client.connect()
        val rejected = client.subscribe(room("r", reject = true))
        val echoed = client.subscribe(room("s"))
        rejected.awaitState(SubscriptionState.Rejected)
        echoed.awaitState(SubscriptionState.Subscribed(1))

        restart()

        echoed.awaitState(SubscriptionState.Subscribed(2), timeout = 15.seconds)
        assertEquals(CableState.Connected, client.state.value)
    }

    @Test
    fun twentyConcurrentPerformsEachArriveExactlyOnce() = e2eTest {
        val client = client()
        client.connect()
        val subscription = client.subscribe(room("burst"))
        subscription.awaitState(SubscriptionState.Subscribed(1))

        val payloads = (1..20).map { JsonPrimitive("burst-$it") }
        val sent = payloads.map { async { subscription.perform("echo", echo(it)) } }.awaitAll()
        val received = within(10.seconds, "20 echoes") { subscription.messages.take(20).toList() }

        assertEquals(List(20) { true }, sent)
        assertEquals(payloads.toSet(), received.toSet())
        delay(1.seconds)
        assertNull(subscription.pendingMessage())
    }

    @Test
    fun twentyHandlesRegisteredBeforeConnectConfirmWithinThreeSecondsOfConnected() = e2eTest {
        val client = client()
        val handles = (1..20).map { client.subscribe(room("pre-$it")) }
        client.connect()
        client.awaitState(CableState.Connected)

        within(3.seconds, "20 confirmations") {
            handles.forEach { handle -> handle.state.first { it == SubscriptionState.Subscribed(1) } }
        }
    }
}
