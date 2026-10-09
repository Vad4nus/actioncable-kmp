package io.github.vad4nus.actioncable.internal.protocol

import kotlinx.serialization.json.JsonElement

internal sealed interface Inbound {
    data object Welcome : Inbound
    data object Ping : Inbound
    data class Disconnect(val reason: String?, val reconnect: Boolean) : Inbound
    data class Confirm(val identifier: String) : Inbound
    data class Reject(val identifier: String) : Inbound
    data class Message(val identifier: String, val message: JsonElement) : Inbound
    data class Unknown(val type: String?) : Inbound
}
