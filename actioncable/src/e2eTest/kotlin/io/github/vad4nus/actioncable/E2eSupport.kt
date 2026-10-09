package io.github.vad4nus.actioncable

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal const val CABLE_URL = "ws://localhost:3000/cable"
internal const val CONTROL_URL = "http://localhost:3000/control"
private const val SERVER_ORIGIN = "http://localhost:3000"

internal expect fun e2eEngine(): HttpClientEngineFactory<HttpClientEngineConfig>

internal expect val unauthorizedRuns: Int

internal expect val largeEchoBytes: Int

internal expect fun blockedEngineThreads(): Int

internal class CapturingLogger : CableLogger {
    private val recorded = MutableStateFlow(emptyList<String>())
    val lines: List<String> get() = recorded.value

    override fun log(level: CableLogger.Level, message: String) {
        recorded.update { it + "$level $message" }
    }
}

internal fun releaseHook(released: CompletableDeferred<Unit>): ClientPlugin<Unit> =
    createClientPlugin("e2eRelease") { onClose { released.complete(Unit) } }

internal fun HttpRequestBuilder.origin() = header(HttpHeaders.Origin, SERVER_ORIGIN)

internal fun room(name: String, reject: Boolean = false): JsonObject = buildJsonObject {
    put("channel", "EchoChannel")
    put("room", name)
    if (reject) put("reject", true)
}

internal fun echo(payload: JsonElement): JsonObject = buildJsonObject { put("payload", payload) }

internal suspend fun <T> within(timeout: Duration, what: String, block: suspend () -> T): T =
    withTimeoutOrNull(timeout) { block() } ?: fail("$what not reached within $timeout")

internal suspend fun ActionCableClient.awaitState(expected: CableState, timeout: Duration = 10.seconds) =
    within(timeout, "client state $expected") { state.first { it == expected } }

internal suspend fun CableSubscription.awaitState(expected: SubscriptionState, timeout: Duration = 10.seconds) =
    within(timeout, "subscription state $expected") { state.first { it == expected } }

internal suspend fun CableSubscription.nextMessage(timeout: Duration = 10.seconds): JsonElement =
    within(timeout, "message") { messages.first() }

internal suspend fun CableSubscription.pendingMessage(): JsonElement? =
    withTimeoutOrNull(100.milliseconds) { messages.first() }

internal class E2e(scope: CoroutineScope) : CoroutineScope by scope {
    private val control = HttpClient(e2eEngine())
    private val clients = mutableListOf<ActionCableClient>()
    private val releases = mutableListOf<CompletableDeferred<Unit>>()

    fun released(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { releases += it }

    fun track(client: ActionCableClient): ActionCableClient = client.also { clients += it }

    fun client(
        url: String = CABLE_URL,
        onRequest: suspend HttpRequestBuilder.() -> Unit = { origin() },
        onUnauthorized: suspend () -> Boolean = { false },
        options: CableOptions.() -> Unit = {},
        httpConfig: HttpClientConfig<*>.() -> Unit = {},
    ): ActionCableClient {
        val released = released()
        return track(
            ActionCableClient(
                url = url,
                onRequest = onRequest,
                onUnauthorized = onUnauthorized,
                options = options,
                httpClientConfig = {
                    install(releaseHook(released))
                    httpConfig()
                },
            ),
        )
    }

    suspend fun connections(): Int = control.get("$CONTROL_URL/connections").bodyAsText().trim().toInt()

    suspend fun restart() {
        control.post("$CONTROL_URL/restart")
    }

    suspend fun disconnectRemotely(clientId: String, reconnect: Boolean) {
        control.post("$CONTROL_URL/disconnect?client_id=$clientId&reconnect=$reconnect")
    }

    suspend fun sunk(): String = control.get("$CONTROL_URL/sunk").bodyAsText()

    suspend fun awaitConnections(count: Int) = within(5.seconds, "$count server connections") {
        while (connections() != count) delay(100)
    }

    suspend fun finish() {
        try {
            clients.forEach { it.close() }
            within(5.seconds, "release of every client") { releases.awaitAll() }
            awaitConnections(0)
        } finally {
            control.close()
        }
    }
}

internal fun e2eTest(timeout: Duration = 60.seconds, body: suspend E2e.() -> Unit): TestResult = runTest(timeout = timeout) {
    withContext(Dispatchers.Default) {
        val e2e = E2e(this)
        val result = runCatching { e2e.body() }
        val cleanup = runCatching { e2e.finish() }
        CableLog.logger = CableLogger.None
        result.getOrThrow()
        cleanup.getOrThrow()
    }
}
