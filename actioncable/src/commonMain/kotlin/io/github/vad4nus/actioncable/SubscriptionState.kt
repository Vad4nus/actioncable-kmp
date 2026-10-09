package io.github.vad4nus.actioncable

public sealed interface SubscriptionState {
    public data object Pending : SubscriptionState
    public data class Subscribed(val confirmations: Int) : SubscriptionState
    public data object Rejected : SubscriptionState
    public data object Unsubscribed : SubscriptionState
}
