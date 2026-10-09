package io.github.vad4nus.actioncable

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

public interface CableSubscription {
    public val identifier: String
    public val state: StateFlow<SubscriptionState>
    public val messages: Flow<JsonElement>
    public suspend fun perform(action: String, data: JsonObject = JsonObject(emptyMap())): Boolean
    public fun unsubscribe()
}
