package io.github.vad4nus.actioncable.internal.client

import io.github.vad4nus.actioncable.CableSubscription
import io.github.vad4nus.actioncable.internal.subscription.CableSubscriptionImpl
import kotlinx.coroutines.CompletableDeferred

internal sealed interface ClientCommand {
    data object Connect : ClientCommand
    data object Disconnect : ClientCommand
    class Subscribe(val identifier: String, val reply: CompletableDeferred<Result<CableSubscription>>) : ClientCommand
    class Unsubscribe(val handle: CableSubscriptionImpl) : ClientCommand
    class Perform(val handle: CableSubscriptionImpl, val frame: String, val reply: CompletableDeferred<Result<Boolean>>) : ClientCommand
}
