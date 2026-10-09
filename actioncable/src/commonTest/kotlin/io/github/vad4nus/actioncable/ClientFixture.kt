package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.client.ClientImpl
import io.github.vad4nus.actioncable.internal.protocol.encodeSubscribe
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class ClientFixture(
    url: String = "ws://fake/cable",
    onRequest: suspend HttpRequestBuilder.() -> Unit = {},
    onUnauthorized: suspend () -> Boolean = { false },
    canConnect: Flow<Boolean> = flowOf(true),
    options: CableOptions = CableOptions(),
    val logger: RecordingCableLogger = RecordingCableLogger(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    callbackContext: CoroutineContext = Dispatchers.Default,
    random: Random = Random.Default,
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    onRelease: () -> Unit = {},
) {
    val server = FakeCableServer(timeSource)
    private val releases = MutableStateFlow(0)
    val releaseCount: Int get() = releases.value

    init {
        CableLog.logger = logger
    }

    val client: ActionCableClient = ClientImpl(
        url = url,
        onRequest = onRequest,
        onUnauthorized = onUnauthorized,
        canConnect = canConnect,
        options = options,
        opener = server.asOpener(),
        release = {
            releases.update { it + 1 }
            onRelease()
        },
        dispatcher = dispatcher,
        callbackContext = callbackContext,
        random = random,
        timeSource = timeSource,
    )

    suspend fun awaitReleased(timeout: Duration = 5.seconds) {
        withTimeout(timeout) { releases.first { it >= 1 } }
    }

    suspend fun assertReleasedOnce() {
        awaitReleased()
        delay(200.milliseconds)
        assertEquals(1, releaseCount)
    }

    suspend fun connected(session: Int = 1): FakeCableServer.FakeWebSocketSession {
        val opened = withTimeout(5.seconds) { server.awaitSession(session) }
        opened.serverSend("""{"type":"welcome"}""")
        withTimeout(5.seconds) { client.state.first { it == CableState.Connected } }
        return opened
    }

    suspend fun subscribed(identifier: JsonObject, session: FakeCableServer.FakeWebSocketSession): CableSubscription {
        val handle = client.subscribe(identifier)
        withTimeout(5.seconds) { session.awaitSent { sent -> encodeSubscribe(handle.identifier) in sent } }
        session.serverSend("""{"type":"confirm_subscription","identifier":${JsonPrimitive(handle.identifier)}}""")
        withTimeout(5.seconds) { handle.state.first { it is SubscriptionState.Subscribed } }
        return handle
    }
}

internal fun realTest(timeout: Duration = 60.seconds, body: suspend CoroutineScope.() -> Unit): TestResult =
    runTest(timeout = timeout) { withContext(Dispatchers.Default) { body() } }

internal fun spin(duration: Duration) {
    val mark = TimeSource.Monotonic.markNow()
    while (mark.elapsedNow() < duration) Unit
}
