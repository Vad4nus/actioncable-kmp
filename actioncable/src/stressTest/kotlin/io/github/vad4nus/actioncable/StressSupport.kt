package io.github.vad4nus.actioncable

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal expect fun liveThreads(): Int

internal expect fun collectGarbage()

internal expect fun weakRef(value: Any): () -> Any?

internal suspend fun E2e.lifecycle(factory: (CompletableDeferred<Unit>) -> ActionCableClient): List<() -> Any?> {
    val released = CompletableDeferred<Unit>()
    val client = factory(released)
    client.connect()
    val subscription = client.subscribe(room("lifecycle"))
    subscription.awaitState(SubscriptionState.Subscribed(1))
    val refs = listOf(weakRef(client), weakRef(subscription))
    client.close()
    within(5.seconds, "release of the HttpClient") { released.await() }
    return refs
}

internal suspend fun survivors(refs: List<() -> Any?>, timeout: Duration = 30.seconds): Int {
    val start = TimeSource.Monotonic.markNow()
    var alive: Int
    do {
        collectGarbage()
        delay(200)
        alive = refs.count { it() != null }
    } while (alive > 0 && start.elapsedNow() < timeout)
    return alive
}

internal suspend fun warmDefaultDispatcher() = withContext(Dispatchers.Default) {
    repeat(64) {
        launch {
            val end = TimeSource.Monotonic.markNow() + 200.milliseconds
            while (end.hasNotPassedNow()) Unit
        }
    }
}

internal suspend fun settledThreads(ceiling: Int, timeout: Duration = 90.seconds): Int {
    val start = TimeSource.Monotonic.markNow()
    var threads: Int
    do {
        delay(1.seconds)
        threads = liveThreads()
    } while (threads > ceiling && start.elapsedNow() < timeout)
    return threads
}

internal const val CHAOS_CABLE_URL = "ws://localhost:3100/cable"
private const val CHAOS_CONTROL_URL = "http://localhost:3101"

internal class StateTimeline(private val origin: TimeMark) {
    private val recorded = MutableStateFlow(emptyList<Pair<Duration, CableState>>())

    fun record(state: CableState) = recorded.update { it + (origin.elapsedNow() to state) }

    fun failedAttempts(): List<Duration> =
        recorded.value.filter { (_, state) -> state is CableState.Connecting && state.attempt > 0 }.map { it.first }

    fun lastConnected(): Duration = recorded.value.last { it.second == CableState.Connected }.first
}

internal fun E2e.timeline(client: ActionCableClient, origin: TimeMark = TimeSource.Monotonic.markNow()): StateTimeline =
    StateTimeline(origin).also { timeline ->
        launch {
            client.state
                .transformWhile { state ->
                    emit(state)
                    state != CableState.Closed
                }
                .collect { timeline.record(it) }
        }
    }

internal class Chaos(private val http: HttpClient) {
    suspend fun mode(name: String, vararg params: Pair<String, Any>) =
        post("/mode?name=$name" + params.joinToString("") { "&${it.first}=${it.second}" })

    suspend fun cut() = post("/cut")

    suspend fun drop() = post("/drop")

    suspend fun stopServer() = post("/server/stop")

    suspend fun startServer() = post("/server/start")

    suspend fun openLinks(): Int =
        Json.parseToJsonElement(http.get("$CHAOS_CONTROL_URL/stats").bodyAsText()).jsonObject.getValue("open").jsonPrimitive.int

    private suspend fun post(path: String) {
        val response = http.post(CHAOS_CONTROL_URL + path)
        check(response.status.value == 200) { "chaos $path: ${response.status} ${response.bodyAsText()}" }
    }
}

internal fun chaosTest(timeout: Duration = 120.seconds, body: suspend E2e.(Chaos) -> Unit): TestResult =
    e2eTest(timeout) {
        val http = HttpClient(e2eEngine()) {
            install(HttpTimeout) {
                requestTimeoutMillis = 90_000
                socketTimeoutMillis = 90_000
            }
        }
        val chaos = Chaos(http)
        try {
            body(chaos)
        } finally {
            withContext(NonCancellable) {
                chaos.startServer()
                chaos.mode("pass")
                http.close()
            }
        }
    }
