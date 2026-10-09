package io.github.vad4nus.actioncable.internal.subscription

import io.github.vad4nus.actioncable.CableLog
import io.github.vad4nus.actioncable.CableOptions
import io.github.vad4nus.actioncable.SubscriptionState
import io.github.vad4nus.actioncable.internal.CableErrors
import io.github.vad4nus.actioncable.internal.protocol.Inbound
import io.github.vad4nus.actioncable.internal.protocol.MessageType
import io.github.vad4nus.actioncable.internal.protocol.encodeSubscribe
import io.github.vad4nus.actioncable.internal.protocol.encodeUnsubscribe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.random.Random

private const val RECEIPT_BASE_MILLIS = 5_000L
private const val RECEIPT_EXPONENT_CAP = 30
private const val RECEIPT_JITTER_PCT = 25L

internal class SubscriptionRegistry(
    private val options: CableOptions,
    private val random: Random,
    private val send: (String) -> Boolean,
) {
    private val handles = mutableMapOf<String, CableSubscriptionImpl>()
    private var serialCounter = 0
    private var sessionScope: CoroutineScope? = null

    fun onReady(scope: CoroutineScope) {
        sessionScope = scope
        for (handle in handles.values) {
            launchSubscribeLoop(handle, scope)
        }
    }

    fun onInbound(inbound: Inbound) {
        when (inbound) {
            is Inbound.Confirm -> {
                val handle = handles[inbound.identifier]
                if (handle != null) {
                    handle.onConfirm()
                } else {
                    send(encodeUnsubscribe(inbound.identifier))
                    CableLog.frameIgnored(MessageType.CONFIRM_SUBSCRIPTION.value)
                }
            }
            is Inbound.Reject -> {
                val handle = handles[inbound.identifier]
                if (handle != null) {
                    handle.onReject()
                } else {
                    CableLog.frameIgnored(MessageType.REJECT_SUBSCRIPTION.value)
                }
            }
            is Inbound.Message -> {
                val handle = handles[inbound.identifier]
                if (handle != null) {
                    handle.deliver(inbound.message)
                } else {
                    CableLog.frameIgnored(null)
                }
            }
            else -> Unit
        }
    }

    fun onLost() {
        sessionScope = null
        for (handle in handles.values) {
            handle.loopJob = null
            handle.onConnectionLost()
        }
    }

    fun subscribe(identifier: String): CableSubscriptionImpl = create(identifier).also(::register)

    fun create(identifier: String): CableSubscriptionImpl {
        val existing = handles[identifier]
        if (existing != null) {
            throw CableErrors.duplicateIdentifier(existing.sub)
        }
        return CableSubscriptionImpl(
            identifier = identifier,
            messageBufferSize = options.messageBufferSize,
            serialNumber = ++serialCounter,
            send = send,
            removeFromRegistry = ::unsubscribe,
        )
    }

    fun register(handle: CableSubscriptionImpl) {
        handles[handle.identifier] = handle
        val scope = sessionScope
        if (scope != null) {
            launchSubscribeLoop(handle, scope)
        }
    }

    fun unsubscribe(handle: CableSubscriptionImpl) {
        if (handles[handle.identifier] !== handle) return
        handles.remove(handle.identifier)
        handle.loopJob?.cancel()
        handle.loopJob = null
        if (handle.subscribeSentThisConnection) {
            send(encodeUnsubscribe(handle.identifier))
        }
        handle.markUnsubscribed()
    }

    fun closeAll() {
        val all = handles.values.toList()
        handles.clear()
        sessionScope = null
        for (handle in all) {
            handle.loopJob?.cancel()
            handle.loopJob = null
            handle.markUnsubscribed()
        }
    }

    private fun launchSubscribeLoop(handle: CableSubscriptionImpl, scope: CoroutineScope) {
        handle.loopJob = scope.launch {
            var m = 0
            while (isActive) {
                val sent = send(encodeSubscribe(handle.identifier))
                if (sent) handle.subscribeSentThisConnection = true
                m++
                val waitMs = receiptWaitMillis(m)
                val result = withTimeoutOrNull(waitMs) {
                    handle.state.first { it !is SubscriptionState.Pending }
                }
                if (result != null) break
            }
        }
    }

    private fun receiptWaitMillis(m: Int): Long {
        val maxMs = options.maxReconnectDelay.inWholeMilliseconds
        val base = (RECEIPT_BASE_MILLIS shl min(m - 1, RECEIPT_EXPONENT_CAP))
            .coerceAtMost(maxMs)
            .coerceAtLeast(RECEIPT_BASE_MILLIS)
        val jitter = base * RECEIPT_JITTER_PCT / 100L
        return (base - jitter + random.nextLong(2 * jitter + 1)).coerceAtLeast(0L)
    }
}
