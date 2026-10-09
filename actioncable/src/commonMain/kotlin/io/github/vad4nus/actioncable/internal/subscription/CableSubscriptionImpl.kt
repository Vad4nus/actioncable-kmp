package io.github.vad4nus.actioncable.internal.subscription

import io.github.vad4nus.actioncable.CableLog
import io.github.vad4nus.actioncable.CableSubscription
import io.github.vad4nus.actioncable.SubscriptionState
import io.github.vad4nus.actioncable.internal.CableErrors
import io.github.vad4nus.actioncable.internal.log.LogEvent
import io.github.vad4nus.actioncable.internal.log.LogField
import io.github.vad4nus.actioncable.internal.log.LogRedaction
import io.github.vad4nus.actioncable.internal.protocol.encodePerform
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal class CableSubscriptionImpl(
    override val identifier: String,
    private val messageBufferSize: Int,
    internal val serialNumber: Int,
    private val send: (String) -> Boolean,
    private val removeFromRegistry: (CableSubscriptionImpl) -> Unit,
) : CableSubscription {

    private val _state = MutableStateFlow<SubscriptionState>(SubscriptionState.Pending)
    override val state: StateFlow<SubscriptionState> = _state.asStateFlow()

    // MutableStateFlow.update takes a short lock per drop; switch to stdlib AtomicLong once it is stable in the minimum supported Kotlin
    internal val lost = MutableStateFlow(0L)

    internal val channel: Channel<JsonElement> = Channel(
        capacity = messageBufferSize,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { lost.update { it + 1 } },
    )

    private val mutex = Mutex()

    private var episodeDropped = 0L
    private var consecutiveNonDrops = 0

    internal var subscribeSentThisConnection = false
    private var confirmedOnCurrentConnection = false
    private var confirmations = 0
    internal var loopJob: Job? = null

    internal val sub: String get() = "${LogField.SUB.value}=${LogRedaction.sub(serialNumber)}"

    override val messages: Flow<JsonElement> = flow {
        if (!mutex.tryLock()) {
            throw CableErrors.concurrentCollection(sub)
        }
        try {
            channel.receiveAsFlow().collect { emit(it) }
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun perform(action: String, data: JsonObject): Boolean =
        performNow(encodePerform(identifier, action, data))

    internal fun performNow(frame: String): Boolean =
        _state.value is SubscriptionState.Subscribed && send(frame)

    override fun unsubscribe() {
        removeFromRegistry(this)
    }

    internal fun onConfirm() {
        if (!confirmedOnCurrentConnection) {
            confirmedOnCurrentConnection = true
            confirmations++
        }
        _state.value = SubscriptionState.Subscribed(confirmations)
    }

    internal fun onReject() {
        _state.value = SubscriptionState.Rejected
    }

    internal fun onConnectionLost() {
        subscribeSentThisConnection = false
        confirmedOnCurrentConnection = false
        _state.value = SubscriptionState.Pending
    }

    internal fun deliver(message: JsonElement) {
        val before = lost.value
        channel.trySend(message)
        val dropped = lost.value - before
        if (dropped > 0L) {
            episodeDropped += dropped
            consecutiveNonDrops = 0
            if (episodeDropped >= messageBufferSize) {
                CableLog.w(LogEvent.BUFFER_OVERFLOW, *overflowFields())
                episodeDropped = 0
            }
        } else if (episodeDropped > 0L) {
            consecutiveNonDrops++
            if (consecutiveNonDrops >= messageBufferSize) {
                CableLog.w(LogEvent.BUFFER_OVERFLOW, *overflowFields())
                episodeDropped = 0
                consecutiveNonDrops = 0
            }
        }
    }

    internal fun markUnsubscribed() {
        if (episodeDropped > 0L) CableLog.d(LogEvent.BUFFER_OVERFLOW_OPEN, *overflowFields())
        _state.value = SubscriptionState.Unsubscribed
        channel.close()
    }

    private fun overflowFields(): Array<Pair<LogField, Any?>> =
        arrayOf(LogField.SUB to LogRedaction.sub(serialNumber), LogField.DROPPED to episodeDropped)
}
