package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.connection.SessionOpener
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

internal class FakeCableServer(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    class Request(val url: String, val headers: Map<String, List<String>>)

    private val _callMarks = MutableStateFlow(emptyList<ComparableTimeMark>())
    private val _endMarks = MutableStateFlow(emptyList<ComparableTimeMark>())
    private val _sessions = MutableStateFlow(emptyList<FakeWebSocketSession>())
    private val _requests = MutableStateFlow(emptyList<Request>())
    private val _cancellations = MutableStateFlow(0)

    val calls: Int get() = _callMarks.value.size
    val cancellations: Int get() = _cancellations.value
    fun callMarks(): List<ComparableTimeMark> = _callMarks.value
    fun endMarks(): List<ComparableTimeMark> = _endMarks.value
    fun sessions(): List<FakeWebSocketSession> = _sessions.value
    fun lastSession(): FakeWebSocketSession = _sessions.value.last()
    fun requests(): List<Request> = _requests.value
    fun openSessions(): Int = _sessions.value.count { !it.ended.value }

    suspend fun awaitCalls(n: Int) {
        _callMarks.first { it.size >= n }
    }

    suspend fun awaitSession(n: Int): FakeWebSocketSession = _sessions.first { it.size >= n }[n - 1]

    @Volatile private var failTimes = 0
    @Volatile private var failure: () -> Throwable = { RuntimeException("fake_handshake_failure") }
    @Volatile private var hangMode = false
    @Volatile private var rendezvousMode = false
    @Volatile private var orphanMode = false

    fun failNext(times: Int = 1, error: () -> Throwable = { RuntimeException("fake_handshake_failure") }) {
        failure = error
        failTimes = times
    }

    fun hangNext() { hangMode = true }
    fun withRendezvousOutgoing() { rendezvousMode = true }
    fun orphanNext() { orphanMode = true }

    private val readerOpen = MutableStateFlow(true)
    fun pauseReader() { readerOpen.value = false }
    fun resumeReader() { readerOpen.value = true }

    fun welcome() = lastSession().serverSend("""{"type":"welcome"}""")
    fun ping() = lastSession().serverSend("""{"type":"ping"}""")
    fun confirm(identifier: String) =
        lastSession().serverSend("""{"type":"confirm_subscription","identifier":${JsonPrimitive(identifier)}}""")
    fun reject(identifier: String) =
        lastSession().serverSend("""{"type":"reject_subscription","identifier":${JsonPrimitive(identifier)}}""")
    fun broadcast(identifier: String, message: JsonElement) =
        lastSession().serverSend("""{"identifier":${JsonPrimitive(identifier)},"message":$message}""")
    fun disconnect(reason: String? = null, reconnect: Boolean = false) {
        val reasonPart = if (reason != null) ""","reason":${JsonPrimitive(reason)}""" else ""
        lastSession().serverSend("""{"type":"disconnect"$reasonPart,"reconnect":$reconnect}""")
    }
    fun drop() = lastSession().closeGracefully()

    fun sendPair(first: String, second: String, reversed: Boolean = false) {
        if (reversed) {
            lastSession().serverSend(second); lastSession().serverSend(first)
        } else {
            lastSession().serverSend(first); lastSession().serverSend(second)
        }
    }

    fun asOpener(): SessionOpener = SessionOpener { request, block ->
        _requests.update { it + Request(request.url.buildString(), request.headers.entries().associate { e -> e.key to e.value }) }
        _callMarks.update { it + timeSource.markNow() }
        var session: FakeWebSocketSession? = null
        try {
            if (failTimes > 0) {
                failTimes--
                throw failure()
            }
            if (hangMode) {
                hangMode = false
                awaitCancellation()
            }
            val opened = FakeWebSocketSession(if (rendezvousMode) Channel(0) else Channel(Channel.UNLIMITED))
            session = opened
            _sessions.update { it + opened }
            if (orphanMode) {
                orphanMode = false
                opened.orphan()
            }
            coroutineScope {
                val reader = launch { opened.readOutgoing() }
                block(opened)
                reader.cancel()
            }
        } catch (e: CancellationException) {
            _cancellations.update { it + 1 }
            throw e
        } finally {
            _endMarks.update { it + timeSource.markNow() }
            session?.ended?.value = true
        }
    }

    inner class FakeWebSocketSession(
        private val outboundChannel: Channel<Frame> = Channel(Channel.UNLIMITED),
    ) : WebSocketSession {
        private val job = Job()
        @Volatile private var orphaned = false
        val inbound = Channel<Frame>(Channel.UNLIMITED)
        val ended = MutableStateFlow(false)
        private val sent = MutableStateFlow(emptyList<String>())
        val clientSent: StateFlow<List<String>> get() = sent

        override val coroutineContext: CoroutineContext = job
        override val incoming: ReceiveChannel<Frame> get() = inbound
        override val outgoing: SendChannel<Frame> get() = outboundChannel
        override val extensions: List<WebSocketExtension<*>> get() = emptyList()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE

        init { job.invokeOnCompletion { if (!orphaned) inbound.cancel() } }

        override suspend fun send(frame: Frame) = outboundChannel.send(frame)
        override suspend fun flush() = Unit

        @Deprecated("Use cancel() instead.")
        override fun terminate() { job.cancel() }

        fun serverSend(text: String) { inbound.trySend(Frame.Text(text)) }
        fun closeGracefully() { inbound.close() }
        fun closeWithError(cause: Throwable) { inbound.close(cause) }

        suspend fun awaitSent(predicate: (List<String>) -> Boolean): List<String> = sent.first(predicate)

        internal suspend fun readOutgoing() {
            try {
                readerOpen.collectLatest { open ->
                    if (open) for (frame in outboundChannel) record(frame)
                }
            } finally {
                if (readerOpen.value) drain()
            }
        }

        fun allClientSent(): List<String> {
            if (readerOpen.value) drain()
            return sent.value
        }

        private fun record(frame: Frame) {
            if (frame is Frame.Text) {
                val text = frame.readText()
                sent.update { it + text }
            }
        }

        private fun drain() {
            var frame = outboundChannel.tryReceive().getOrNull()
            while (frame != null) {
                record(frame)
                frame = outboundChannel.tryReceive().getOrNull()
            }
        }

        suspend fun cancelSession() { job.cancelAndJoin() }

        fun orphan() {
            orphaned = true
            job.cancel()
        }
    }
}
