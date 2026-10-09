package io.github.vad4nus.actioncable.internal.client

import io.github.vad4nus.actioncable.CableLog
import io.github.vad4nus.actioncable.CableSubscription
import io.github.vad4nus.actioncable.internal.protocol.encodePerform
import io.github.vad4nus.actioncable.internal.subscription.CableSubscriptionImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.JsonObject

internal class SubscriptionHandle(
    private val impl: CableSubscriptionImpl,
    private val commands: SendChannel<ClientCommand>,
) : CableSubscription by impl {

    override suspend fun perform(action: String, data: JsonObject): Boolean {
        val frame = encodePerform(identifier, action, data)
        val reply = CompletableDeferred<Result<Boolean>>()
        if (!commands.trySend(ClientCommand.Perform(impl, frame, reply)).isSuccess) {
            CableLog.closedCall(ClientOperation.PERFORM)
            return false
        }
        val result = try {
            reply.await()
        } catch (e: CancellationException) {
            reply.cancel()
            throw e
        }
        return result.getOrThrow()
    }

    override fun unsubscribe() {
        if (!commands.trySend(ClientCommand.Unsubscribe(impl)).isSuccess) CableLog.closedCall(ClientOperation.UNSUBSCRIBE)
    }
}
