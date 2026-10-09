package io.github.vad4nus.actioncable.internal.protocol

import io.github.vad4nus.actioncable.internal.CableErrors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val cableJson = Json {
    ignoreUnknownKeys = true
}

internal fun decodeInbound(text: String): Inbound? {
    val obj = runCatching {
        cableJson.parseToJsonElement(text).jsonObject
    }.getOrNull() ?: return null

    val type = obj.stringOrNull(ProtocolKey.TYPE)
    val identifier = obj.stringOrNull(ProtocolKey.IDENTIFIER)

    if (type == null) {
        val message = obj[ProtocolKey.MESSAGE.value] ?: return Inbound.Unknown(null)
        if (identifier == null) return Inbound.Unknown(null)
        return Inbound.Message(identifier, message)
    }

    return when (MessageType.of(type)) {
        MessageType.WELCOME -> Inbound.Welcome
        MessageType.PING -> Inbound.Ping
        MessageType.DISCONNECT -> decodeDisconnect(obj) ?: Inbound.Unknown(type)
        MessageType.CONFIRM_SUBSCRIPTION -> identifier?.let(Inbound::Confirm) ?: Inbound.Unknown(type)
        MessageType.REJECT_SUBSCRIPTION -> identifier?.let(Inbound::Reject) ?: Inbound.Unknown(type)
        null -> Inbound.Unknown(type)
    }
}

internal fun encodeSubscribe(identifier: String): String = encodeCommand(ProtocolCommand.SUBSCRIBE, identifier)

internal fun encodeUnsubscribe(identifier: String): String = encodeCommand(ProtocolCommand.UNSUBSCRIBE, identifier)

internal fun encodePerform(identifier: String, action: String, data: JsonObject): String {
    val dataWithAction = buildJsonObject {
        data.entries.forEach { (k, v) -> put(k, v) }
        put(ProtocolKey.ACTION.value, action)
    }
    val dataStr = runCatching {
        cableJson.encodeToString(JsonElement.serializer(), dataWithAction)
    }.getOrElse {
        throw CableErrors.unencodableData()
    }
    return encodeCommand(ProtocolCommand.MESSAGE, identifier) {
        put(ProtocolKey.DATA.value, dataStr)
    }
}

internal fun identifierOf(obj: JsonObject): String {
    val channel = obj[ProtocolKey.CHANNEL.value]
    if (channel == null || channel !is JsonPrimitive || !channel.isString) {
        throw CableErrors.missingChannel()
    }
    return runCatching {
        cableJson.encodeToString(JsonElement.serializer(), obj)
    }.getOrElse {
        throw CableErrors.unencodableIdentifier()
    }
}

private fun decodeDisconnect(obj: JsonObject): Inbound.Disconnect? {
    val reason = runCatching { obj[ProtocolKey.REASON.value]?.jsonPrimitive?.contentOrNull }.getOrElse { return null }
    val reconnect = when (val raw = obj[ProtocolKey.RECONNECT.value]) {
        null -> false
        else -> {
            val p = runCatching { raw.jsonPrimitive }.getOrNull() ?: return null
            if (p.isString) return null
            p.content.toBooleanStrictOrNull() ?: return null
        }
    }
    return Inbound.Disconnect(reason, reconnect)
}

private fun JsonObject.stringOrNull(key: ProtocolKey): String? =
    runCatching { this[key.value]?.jsonPrimitive?.contentOrNull }.getOrNull()

private inline fun encodeCommand(
    command: ProtocolCommand,
    identifier: String,
    extra: JsonObjectBuilder.() -> Unit = {},
): String = buildJsonObject {
    put(ProtocolKey.COMMAND.value, command.value)
    put(ProtocolKey.IDENTIFIER.value, identifier)
    extra()
}.toString()
