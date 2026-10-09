package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.protocol.encodePerform
import io.github.vad4nus.actioncable.internal.protocol.encodeSubscribe
import io.github.vad4nus.actioncable.internal.protocol.encodeUnsubscribe
import io.github.vad4nus.actioncable.internal.protocol.identifierOf
import io.ktor.websocket.Frame
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test

class CableModelTest {

    @Test
    fun randomOperationSequencesMatchTheModel() = runModel(seeds = 0 until 100, steps = 200)
}

internal fun runModel(seeds: IntRange, steps: Int) {
    for (seed in seeds) runTest { CableModel(this, seed).run(steps) }
}

private const val STALE_MS = 15_000L
private const val RECONNECT_LIMIT_MS = 40_000L
private const val STALE_MARGIN_MS = 300L
private const val HEALTHY_MS = 10_000L
private const val PING_MS = 3_000L
private val NAMES = List(6) { "r$it" }
private val GARBAGE = listOf(
    "not json",
    "",
    "[1,2]",
    "42",
    "{}",
    """{"type":"unknown"}""",
    """{"type":null}""",
    """{"type":"confirm_subscription"}""",
    """{"type":"reject_subscription","identifier":7}""",
    """{"message":1}""",
    """{"identifier":"{\"channel\":\"Ghost\"}","message":1}""",
)

internal class CableModel(private val test: TestScope, private val seed: Int) {
    private val random = Random(seed)
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    private val gate = MutableStateFlow(true)
    private var refresh = false
    private var refreshCalls = 0
    private val fixture = ClientFixture(
        onUnauthorized = {
            refreshCalls++
            refresh
        },
        canConnect = gate,
        options = CableOptions().apply { messageBufferSize = 10_000 },
        dispatcher = dispatcher,
        callbackContext = dispatcher,
        random = Random(seed),
        timeSource = test.testScheduler.timeSource,
    )
    private val client = fixture.client
    private val server = fixture.server
    private val trace = ArrayDeque<String>()

    private var armed = false
    private var stopped: String? = null
    private var sessions = 0
    private var welcomed = false
    private var welcomedAt = 0L
    private var lastInbound = 0L
    private var streak = 0
    private var expectedRefreshCalls = 0
    private var serial = 0
    private val live = mutableMapOf<String, Tracked>()
    private val all = mutableListOf<Tracked>()

    private val now: Long get() = test.testScheduler.currentTime
    private val open: Boolean get() = armed && gate.value

    private inner class Tracked(val name: String, val handle: CableSubscription) {
        var expected: SubscriptionState = SubscriptionState.Pending
        var confirmations = 0
        var confirmedThisSession = false
        var subscribedOn = 0
        val delivered = mutableListOf<JsonElement>()
        val received = mutableListOf<JsonElement>()
        val collector: Job = test.backgroundScope.launch(dispatcher) { handle.messages.collect { received += it } }
    }

    suspend fun run(steps: Int) {
        try {
            repeat(steps) { step ->
                check(server.calls == sessions) { "unexpected connection attempt: ${server.calls} calls, $sessions expected" }
                val action = pick()
                trace.addLast("$step $action t=$now")
                if (trace.size > 40) trace.removeFirst()
                act(action)
                verify()
            }
            closeAndVerify()
        } catch (e: Throwable) {
            throw AssertionError("model seed=$seed failed: ${e.message}\n${trace.joinToString("\n")}", e)
        } finally {
            client.close()
        }
    }

    private fun pick(): String {
        val choices = buildList {
            add("connect" to if (armed) 1 else 8)
            add("disconnect" to 1)
            add("gate" to if (gate.value) 1 else 8)
            add("subscribe" to 6)
            add("time" to 3)
            if (live.isNotEmpty()) {
                add("unsubscribe" to 3)
                add("perform" to 6)
            }
            if (open) {
                add((if (welcomed) "rewelcome" else "welcome") to (if (welcomed) 1 else 15))
                add("broadcast" to 12)
                add("ping" to 2)
                add("garbage" to 2)
                add("drop" to 1)
                add("reconnectFrame" to 1)
                add("stopFrame" to 1)
                add("authFrame" to 1)
                add("pingedIdle" to 1)
            }
            if (welcomed && live.values.any { it.subscribedOn == sessions }) {
                add("confirm" to 15)
                add("reject" to 1)
            }
            if (welcomed && live.size < NAMES.size) add("staleConfirm" to 1)
        }
        var roll = random.nextInt(choices.sumOf { it.second })
        for ((name, weight) in choices) {
            roll -= weight
            if (roll < 0) return name
        }
        error("unreachable")
    }

    private suspend fun act(action: String) {
        when (action) {
            "connect" -> {
                client.connect()
                runCurrent()
                if (!armed) {
                    armed = true
                    stopped = null
                    streak = 0
                    if (gate.value) awaitNewSession()
                }
            }
            "disconnect" -> {
                client.disconnect()
                runCurrent()
                lose()
                armed = false
                stopped = null
            }
            "gate" -> {
                gate.value = !gate.value
                runCurrent()
                if (armed) {
                    if (gate.value) {
                        awaitNewSession()
                    } else {
                        lose()
                        streak = 0
                    }
                }
            }
            "subscribe" -> subscribe(NAMES.random(random))
            "unsubscribe" -> unsubscribe(live.keys.sorted().random(random))
            "perform" -> perform(live.keys.sorted().random(random))
            "welcome" -> {
                server.welcome()
                runCurrent()
                welcomed = true
                welcomedAt = now
                inbound()
                live.values.forEach { it.subscribedOn = sessions }
                live.values.forEach { awaitSent(encodeSubscribe(it.handle.identifier)) }
            }
            "rewelcome" -> serverFrame { server.welcome() }
            "ping" -> serverFrame { server.ping() }
            "garbage" -> serverFrame {
                if (random.nextInt(4) == 0) {
                    server.lastSession().inbound.trySend(Frame.Binary(true, byteArrayOf(1, 2, 3)))
                } else {
                    server.lastSession().serverSend(GARBAGE.random(random))
                }
            }
            "broadcast" -> {
                val name = NAMES.random(random)
                val value = JsonPrimitive(serial++)
                server.broadcast(identifierOf(room(name)), value)
                runCurrent()
                if (welcomed) {
                    inbound()
                    live[name]?.delivered?.add(value)
                }
            }
            "confirm" -> {
                val tracked = live.values.filter { it.subscribedOn == sessions }.sortedBy { it.name }.random(random)
                server.confirm(tracked.handle.identifier)
                runCurrent()
                inbound()
                if (!tracked.confirmedThisSession) {
                    tracked.confirmedThisSession = true
                    tracked.confirmations++
                }
                tracked.expected = SubscriptionState.Subscribed(tracked.confirmations)
            }
            "reject" -> {
                val tracked = live.values.filter { it.subscribedOn == sessions }.sortedBy { it.name }.random(random)
                server.reject(tracked.handle.identifier)
                runCurrent()
                inbound()
                tracked.expected = SubscriptionState.Rejected
            }
            "staleConfirm" -> {
                val id = identifierOf(room(NAMES.filter { it !in live }.random(random)))
                val before = sentCount(encodeUnsubscribe(id))
                server.confirm(id)
                runCurrent()
                inbound()
                awaitSent(encodeUnsubscribe(id), before + 1)
            }
            "drop" -> {
                server.drop()
                runCurrent()
                lose()
                streak = 0
                awaitNewSession()
            }
            "reconnectFrame" -> {
                server.disconnect("server_restart", reconnect = true)
                runCurrent()
                lose()
                streak = 0
                awaitNewSession()
            }
            "stopFrame" -> stopWith("remote") { server.disconnect("remote", reconnect = false) }
            "authFrame" -> unauthorized(listOf("unauthorized", "token_expired").random(random))
            "pingedIdle" -> repeat(6) {
                passTime(PING_MS)
                if (open) serverFrame { server.ping() }
            }
            "time" -> passTime(listOf(100L, 2_000L, 6_000L, 16_000L, 40_000L).random(random))
            else -> error("unknown action $action")
        }
    }

    private suspend fun subscribe(name: String) {
        if (name in live) {
            val failure = runCatching { client.subscribe(room(name)) }.exceptionOrNull()
            check(failure is IllegalStateException && "duplicate_identifier" in failure.message.orEmpty()) {
                "duplicate subscribe of $name gave $failure"
            }
            return
        }
        val tracked = Tracked(name, client.subscribe(room(name)))
        runCurrent()
        live[name] = tracked
        all += tracked
        if (welcomed) {
            tracked.subscribedOn = sessions
            awaitSent(encodeSubscribe(tracked.handle.identifier))
        }
    }

    private fun unsubscribe(name: String) {
        val tracked = live.getValue(name)
        val frame = encodeUnsubscribe(tracked.handle.identifier)
        val sentOnThisSession = welcomed && tracked.subscribedOn == sessions
        val before = if (sentOnThisSession) sentCount(frame) else 0
        tracked.handle.unsubscribe()
        runCurrent()
        live.remove(name)
        tracked.expected = SubscriptionState.Unsubscribed
        if (sentOnThisSession) awaitSent(frame, before + 1)
    }

    private suspend fun perform(name: String) {
        val tracked = live.getValue(name)
        val data = buildJsonObject { put("n", serial++) }
        val accepted = tracked.handle.perform("speak", data)
        runCurrent()
        val expected = tracked.expected is SubscriptionState.Subscribed
        check(accepted == expected) { "perform on $name in ${tracked.expected} returned $accepted" }
        if (accepted) awaitSent(encodePerform(tracked.handle.identifier, "speak", data))
    }

    private fun serverFrame(send: () -> Unit) {
        val before = if (open) server.lastSession().allClientSent().size else 0
        send()
        runCurrent()
        if (welcomed) inbound()
        if (open) check(server.lastSession().allClientSent().size == before) { "client answered a frame that needs no answer" }
    }

    private fun unauthorized(reason: String) {
        refresh = random.nextBoolean()
        if (welcomed && now - welcomedAt >= HEALTHY_MS) streak = 0
        server.disconnect(reason)
        runCurrent()
        lose()
        if (++streak >= 2) {
            armed = false
            stopped = reason
            return
        }
        expectedRefreshCalls++
        if (refresh) {
            val before = now
            awaitNewSession()
            check(now == before) { "refreshed reconnect waited ${now - before} ms" }
        } else {
            armed = false
            stopped = reason
        }
    }

    private fun stopWith(reason: String, send: () -> Unit) {
        send()
        runCurrent()
        lose()
        armed = false
        stopped = reason
    }

    private fun passTime(millis: Long) {
        if (!open) {
            advance(millis)
            return
        }
        val remaining = lastInbound + STALE_MS - now
        if (millis < remaining - 2 * STALE_MARGIN_MS) {
            advance(millis)
            return
        }
        advance(remaining + STALE_MARGIN_MS)
        lose()
        streak = 0
        awaitNewSession()
    }

    private fun inbound() {
        lastInbound = now
    }

    private fun lose() {
        if (welcomed) {
            live.values.forEach {
                it.expected = SubscriptionState.Pending
                it.confirmedThisSession = false
            }
        }
        welcomed = false
    }

    private fun awaitNewSession() {
        val deadline = now + RECONNECT_LIMIT_MS
        while (server.calls == sessions) {
            check(now < deadline) { "no reconnect within $RECONNECT_LIMIT_MS ms" }
            advance(10)
        }
        sessions++
        check(server.calls == sessions) { "${server.calls} connection attempts, $sessions expected" }
        welcomed = false
        lastInbound = now
    }

    private fun advance(millis: Long) {
        test.testScheduler.advanceTimeBy(millis)
        runCurrent()
    }

    private fun runCurrent() = test.testScheduler.runCurrent()

    private fun sentCount(frame: String): Int = server.lastSession().allClientSent().count { it == frame }

    private fun awaitSent(frame: String, count: Int = 1) {
        val actual = sentCount(frame)
        check(actual >= count) { "expected $count x $frame on session $sessions, saw $actual" }
    }

    private fun verify() {
        val state = client.state.value
        val expected = when {
            stopped != null -> CableState.Stopped(stopped)
            !open -> CableState.Disconnected
            welcomed -> CableState.Connected
            else -> null
        }
        if (expected == null) check(state is CableState.Connecting) { "state $state, expected Connecting" }
        else check(state == expected) { "state $state, expected $expected" }
        check(server.openSessions() == if (open) 1 else 0) { "${server.openSessions()} open sessions, open=$open" }
        all.forEach { tracked ->
            check(tracked.handle.state.value == tracked.expected) {
                "${tracked.name} is ${tracked.handle.state.value}, expected ${tracked.expected}"
            }
            check(tracked.received == tracked.delivered) {
                "${tracked.name} received ${tracked.received}, expected ${tracked.delivered}"
            }
        }
        check(refreshCalls == expectedRefreshCalls) { "onUnauthorized called $refreshCalls times, expected $expectedRefreshCalls" }
        check(fixture.logger.errors().isEmpty()) { "errors ${fixture.logger.errors()}" }
    }

    private suspend fun closeAndVerify() {
        client.close()
        runCurrent()
        check(client.state.value == CableState.Closed)
        check(server.openSessions() == 0)
        check(fixture.releaseCount == 1) { "released ${fixture.releaseCount} times" }
        all.forEach { tracked ->
            check(tracked.handle.state.value == SubscriptionState.Unsubscribed) { "${tracked.name} ${tracked.handle.state.value}" }
            check(tracked.collector.isCompleted) { "${tracked.name} collector still active" }
            check(tracked.received == tracked.delivered)
        }
        client.connect()
        val late = client.subscribe(room("late"))
        advance(RECONNECT_LIMIT_MS)
        check(server.calls == sessions) { "connection attempt after close" }
        check(late.state.value == SubscriptionState.Unsubscribed)
        check(fixture.logger.errors().isEmpty()) { "errors ${fixture.logger.errors()}" }
    }

    private fun room(name: String): JsonObject = buildJsonObject {
        put("channel", "RoomChannel")
        put("room", name)
    }
}
