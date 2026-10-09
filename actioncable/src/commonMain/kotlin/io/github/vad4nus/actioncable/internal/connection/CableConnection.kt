package io.github.vad4nus.actioncable.internal.connection

import io.github.vad4nus.actioncable.CableLog
import io.github.vad4nus.actioncable.CableOptions
import io.github.vad4nus.actioncable.CableState
import io.github.vad4nus.actioncable.internal.log.LogEvent
import io.github.vad4nus.actioncable.internal.log.LogField
import io.github.vad4nus.actioncable.internal.log.LogRedaction
import io.github.vad4nus.actioncable.internal.protocol.DisconnectReason
import io.github.vad4nus.actioncable.internal.protocol.Inbound
import io.github.vad4nus.actioncable.internal.protocol.MessageType
import io.github.vad4nus.actioncable.internal.protocol.decodeInbound
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.TimeSource

private const val HEALTHY_MILLIS = 10_000L
private const val EXPONENT_CAP = 30
private const val ORPHAN_GRACE_MILLIS = 1_000L

internal class CableConnection(
    private val url: String,
    private val onRequest: suspend HttpRequestBuilder.() -> Unit,
    private val onUnauthorized: suspend () -> Boolean,
    canConnect: Flow<Boolean>,
    private val options: CableOptions,
    private val opener: SessionOpener,
    private val controlDispatcher: CoroutineDispatcher,
    private val callbackContext: CoroutineContext,
    private val random: Random,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val onReady: (CoroutineScope) -> Unit = {},
    private val onInbound: (Inbound) -> Unit = {},
    private val onLost: () -> Unit = {},
) {
    val state: MutableStateFlow<CableState> = MutableStateFlow(CableState.Disconnected)

    private val host = LogRedaction.host(url)

    private val gate = MutableStateFlow<Boolean?>(null)
    private var writer: Channel<String>? = null
    private var attempt = 0
    private var streak = 0
    private var armed = false
    private var loop: Job? = null

    init {
        scope.launch(controlDispatcher) { collectGate(canConnect) }
    }

    suspend fun start() {
        if (armed || state.value is CableState.Closed) return
        loop?.join()
        if (state.value is CableState.Closed) return
        attempt = 0
        streak = 0
        armed = true
        loop = scope.launch(controlDispatcher) { runLoop() }
    }

    suspend fun stop() {
        armed = false
        loop?.cancelAndJoin()
        loop = null
        setState(CableState.Disconnected)
    }

    fun send(text: String): Boolean = writer?.trySend(text)?.isSuccess == true

    fun markClosed(): Boolean {
        if (state.getAndUpdate { CableState.Closed } is CableState.Closed) return false
        CableLog.state(host, CableState.Closed)
        return true
    }

    private suspend fun collectGate(canConnect: Flow<Boolean>) {
        var failures = 0
        while (true) {
            try {
                canConnect.flowOn(callbackContext).distinctUntilChanged().collect { gate.value = it }
                return
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                CableLog.e(LogEvent.GATE_FAILED, LogField.HOST to host, LogField.CLASS to LogRedaction.classChain(e))
                delay(backoffMillis(++failures))
            }
        }
    }

    private suspend fun runLoop() {
        while (true) {
            if (gate.value != true) {
                setState(CableState.Disconnected)
                if (gate.value == null) CableLog.i(LogEvent.GATE_WAITING, LogField.HOST to host)
                gate.first { it == true }
            }
            setState(CableState.Connecting(attempt))
            when (val outcome = gated { runAttempt(attempt) }) {
                null -> streak = 0
                AttemptOutcome.Failure -> if (!fail()) return
                is AttemptOutcome.Healthy -> {
                    setState(CableState.Connecting(attempt))
                    if (outcome.delay) pause(random.nextLong(options.minReconnectDelay.inWholeMilliseconds + 1))
                }
                is AttemptOutcome.Stop -> return stopWith(outcome.reason)
                is AttemptOutcome.Unauthorized -> if (!unauthorized(outcome.reason)) return
            }
        }
    }

    private suspend fun fail(): Boolean {
        attempt++
        streak = 0
        if (attempt > options.maxReconnectAttempts) {
            stopWith(DisconnectReason.MAX_RECONNECT_ATTEMPTS.value)
            return false
        }
        setState(CableState.Connecting(attempt))
        pause(backoffMillis(attempt))
        return true
    }

    private suspend fun unauthorized(reason: String?): Boolean {
        if (++streak >= 2) {
            stopWith(reason)
            return false
        }
        setState(CableState.Connecting(attempt))
        val handled = gated { callUnauthorized() }
        if (handled == null) {
            streak = 0
            return true
        }
        return handled.fold(
            onSuccess = { refreshed ->
                if (!refreshed) stopWith(reason)
                refreshed
            },
            onFailure = { fail() },
        )
    }

    private suspend fun callUnauthorized(): Result<Boolean> = try {
        Result.success(withContext(callbackContext) { onUnauthorized() })
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        CableLog.w(
            LogEvent.UNAUTHORIZED_CALLBACK_FAILED,
            LogField.HOST to host,
            LogField.ATTEMPT to attempt,
            LogField.CLASS to LogRedaction.classChain(e),
        )
        Result.failure(e)
    }

    private suspend fun pause(millis: Long) {
        if (gated { delay(millis) } == null) streak = 0
    }

    private suspend fun <T> gated(block: suspend () -> T): T? = try {
        coroutineScope {
            val watcher = launch {
                gate.first { it != true }
                throw GateClosedException()
            }
            block().also { watcher.cancel() }
        }
    } catch (e: GateClosedException) {
        null
    }

    private suspend fun runAttempt(n: Int): AttemptOutcome {
        val session = SessionState(timeSource.markNow())
        return try {
            coroutineScope {
                launch { watchdog(session) }
                val request = try {
                    withContext(callbackContext) { HttpRequestBuilder().apply { cableRequest(this@CableConnection.url, onRequest) } }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    attemptFailed(LogEvent.REQUEST_CALLBACK_FAILED, n, e)
                    coroutineContext.cancelChildren()
                    return@coroutineScope AttemptOutcome.Failure
                }
                var outcome: AttemptOutcome = AttemptOutcome.Failure
                opener.withSession(request) { ws -> outcome = runSession(ws, session) }
                coroutineContext.cancelChildren()
                outcome
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (session.welcomed) {
                attemptFailed(LogEvent.SESSION_FAILED, n, e)
                loss(session, stale = e is StaleTimeoutException)
            } else {
                attemptFailed(LogEvent.HANDSHAKE_FAILED, n, e)
                AttemptOutcome.Failure
            }
        }
    }

    private suspend fun watchdog(session: SessionState) {
        while (true) {
            val remaining = options.staleTimeout - session.deadlineBase().elapsedNow()
            if (!remaining.isPositive()) throw StaleTimeoutException()
            delay(remaining)
        }
    }

    private suspend fun runSession(ws: WebSocketSession, session: SessionState): AttemptOutcome {
        val queue = Channel<String>(Channel.UNLIMITED)
        try {
            return coroutineScope {
                // Unbounded writer queue; the watchdog bounds it only while inbound traffic stops too, add a cap if an outbound-only stall shows up
                launch { for (text in queue) ws.outgoing.send(Frame.Text(text)) }
                launch { endIfOrphaned(ws) }
                val outcome = pump(ws, session, queue, this)
                coroutineContext.cancelChildren()
                outcome
            }
        } finally {
            writer = null
            queue.close()
            if (session.welcomed) onLost()
        }
    }

    // Ktor 3.0-3.3 never closes incoming when the engine session dies before start(), seen on Darwin after an immediate server close; Ktor 3.4 fixed it but needs Kotlin 2.3 on iOS, drop this once the Ktor floor reaches 3.4
    private suspend fun endIfOrphaned(ws: WebSocketSession) {
        ws.coroutineContext[Job]?.join() ?: return
        delay(ORPHAN_GRACE_MILLIS)
        throw StaleTimeoutException()
    }

    private suspend fun pump(
        ws: WebSocketSession,
        session: SessionState,
        queue: Channel<String>,
        sessionScope: CoroutineScope,
    ): AttemptOutcome {
        for (frame in ws.incoming) {
            if (session.welcomed) session.lastFrame = timeSource.markNow()
            when (val inbound = (frame as? Frame.Text)?.let { decodeInbound(it.readText()) }) {
                null -> CableLog.frameIgnored(null)
                Inbound.Welcome -> if (!session.welcomed) {
                    session.welcomed = true
                    session.lastFrame = timeSource.markNow()
                    writer = queue
                    sessionScope.launch {
                        delay(HEALTHY_MILLIS)
                        session.healthy = true
                        attempt = 0
                        streak = 0
                    }
                    setState(CableState.Connected)
                    onReady(sessionScope)
                }
                Inbound.Ping -> Unit
                is Inbound.Disconnect -> return when (DisconnectReason.of(inbound.reason)) {
                    DisconnectReason.UNAUTHORIZED, DisconnectReason.TOKEN_EXPIRED -> AttemptOutcome.Unauthorized(inbound.reason)
                    else -> if (inbound.reconnect) loss(session, stale = false) else AttemptOutcome.Stop(inbound.reason)
                }
                is Inbound.Unknown -> CableLog.frameIgnored(inbound.type)
                is Inbound.Confirm -> if (session.welcomed) onInbound(inbound) else CableLog.frameIgnored(MessageType.CONFIRM_SUBSCRIPTION.value)
                is Inbound.Reject -> if (session.welcomed) onInbound(inbound) else CableLog.frameIgnored(MessageType.REJECT_SUBSCRIPTION.value)
                is Inbound.Message -> if (session.welcomed) onInbound(inbound) else CableLog.frameIgnored(null)
            }
        }
        return loss(session, stale = false)
    }

    private fun loss(session: SessionState, stale: Boolean): AttemptOutcome =
        if (session.healthy) AttemptOutcome.Healthy(delay = !stale) else AttemptOutcome.Failure

    private fun attemptFailed(event: LogEvent, attempt: Int, e: Exception) =
        CableLog.w(event, LogField.HOST to host, LogField.ATTEMPT to attempt, LogField.CLASS to LogRedaction.classChain(e))

    private fun stopWith(reason: String?) {
        armed = false
        setState(CableState.Stopped(reason))
    }

    private fun setState(new: CableState) {
        val old = state.getAndUpdate { if (it is CableState.Closed) it else new }
        if (old != new && old !is CableState.Closed) CableLog.state(host, new)
    }

    private fun backoffMillis(n: Int): Long {
        val base = (options.minReconnectDelay * (1 shl min(n - 1, EXPONENT_CAP)))
            .coerceAtMost(options.maxReconnectDelay)
            .inWholeMilliseconds
            .coerceAtMost(Long.MAX_VALUE / 4)
        val jitter = base / 4
        return base - jitter + random.nextLong(2 * jitter + 1)
    }
}
