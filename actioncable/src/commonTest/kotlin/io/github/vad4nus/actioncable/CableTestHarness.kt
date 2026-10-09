package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.connection.CableConnection
import io.github.vad4nus.actioncable.internal.protocol.Inbound
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.random.Random

internal class CableTestHarness(
    testScope: TestScope,
    canConnect: Flow<Boolean> = flowOf(true),
    options: CableOptions = CableOptions(),
    onRequest: suspend HttpRequestBuilder.() -> Unit = {},
    onUnauthorized: suspend () -> Boolean = { false },
    onReady: (CoroutineScope) -> Unit = {},
    onInbound: (Inbound) -> Unit = {},
    onLost: () -> Unit = {},
    url: String = "ws://fake/cable",
    seed: Long = 42,
    logger: CableLogger? = null,
) {
    val scheduler = testScope.testScheduler
    val server = FakeCableServer(timeSource = scheduler.timeSource)
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val origin = scheduler.timeSource.markNow()
    private val originTime = scheduler.currentTime
    val log = RecordingCableLogger()
    val states = mutableListOf<CableState>()

    init {
        CableLog.logger = logger ?: log
    }

    val conn = CableConnection(
        url = url,
        onRequest = onRequest,
        onUnauthorized = onUnauthorized,
        canConnect = canConnect,
        options = options,
        opener = server.asOpener(),
        controlDispatcher = dispatcher,
        callbackContext = dispatcher,
        random = Random(seed),
        scope = testScope.backgroundScope,
        timeSource = scheduler.timeSource,
        onReady = onReady,
        onInbound = onInbound,
        onLost = onLost,
    )

    init {
        testScope.backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { conn.state.collect { states += it } }
    }

    val state get() = conn.state

    suspend fun start() = conn.start()

    suspend fun stop() = conn.stop()

    fun now(): Long = scheduler.currentTime

    fun settle() = scheduler.runCurrent()

    fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    fun advanceUntil(step: Long = 10, condition: () -> Boolean) {
        var waited = 0L
        while (!condition()) {
            check(waited < 7_200_000) { "condition not reached within two hours of virtual time" }
            advance(step)
            waited += step
        }
    }

    fun callTimes(): List<Long> = server.callMarks().map { originTime + (it - origin).inWholeMilliseconds }
}

internal class RecordingCableLogger : CableLogger {
    data class Entry(val level: CableLogger.Level, val message: String)

    private val recorded = MutableStateFlow(emptyList<Entry>())
    val entries: List<Entry> get() = recorded.value

    override fun log(level: CableLogger.Level, message: String) {
        recorded.update { it + Entry(level, message) }
    }

    fun warns(): List<String> = entries.filter { it.level == CableLogger.Level.WARN }.map { it.message }
    fun errors(): List<String> = entries.filter { it.level == CableLogger.Level.ERROR }.map { it.message }
    fun lines(): List<String> = entries.map { it.message }
}
