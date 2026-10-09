package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.protocol.decodeInbound
import io.github.vad4nus.actioncable.internal.protocol.encodePerform
import io.github.vad4nus.actioncable.internal.protocol.encodeSubscribe
import io.github.vad4nus.actioncable.internal.protocol.identifierOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class CableFuzzTest {

    private val keys = listOf("type", "identifier", "message", "reason", "reconnect", "sid", "channel", "action", "")
    private val types = listOf("welcome", "ping", "disconnect", "confirm_subscription", "reject_subscription", "x")
    private val chars = "aZ09 \"\\/\u0000\u001f\u007fé中😀{}[],:"

    @Test
    fun decodingRandomFramesNeverThrows() {
        val random = Random(20261003)
        repeat(30_000) {
            val text = when (random.nextInt(3)) {
                0 -> frame(random).toString()
                1 -> corrupt(frame(random).toString(), random)
                else -> text(random, 40)
            }
            try {
                decodeInbound(text)
            } catch (e: Exception) {
                fail("decodeInbound threw ${e::class.simpleName} for $text", e)
            }
        }
    }

    @Test
    fun encodedCommandsRoundTripRandomIdentifiersAndData() {
        val random = Random(20261004)
        repeat(5_000) {
            val identifier = JsonObject(mapOf("channel" to JsonPrimitive(text(random, 8))) + objectOf(random, 2).filterKeys { it != "channel" })
            val id = identifierOf(identifier)
            assertEquals(identifier, Json.parseToJsonElement(id))
            assertEquals(id, Json.parseToJsonElement(encodeSubscribe(id)).jsonObject.getValue("identifier").jsonPrimitive.content)
            val data = JsonObject(objectOf(random, 3))
            val action = text(random, 6)
            val command = Json.parseToJsonElement(encodePerform(id, action, data)).jsonObject
            assertEquals(id, command.getValue("identifier").jsonPrimitive.content)
            assertEquals(JsonObject(data + ("action" to JsonPrimitive(action))), Json.parseToJsonElement(command.getValue("data").jsonPrimitive.content))
        }
    }

    private fun frame(random: Random): JsonObject = JsonObject(
        List(random.nextInt(5)) {
            val key = keys.random(random)
            key to if (key == "type" && random.nextBoolean()) JsonPrimitive(types.random(random)) else element(random, 2)
        }.toMap(),
    )

    private fun element(random: Random, depth: Int): JsonElement = when (random.nextInt(if (depth > 0) 9 else 7)) {
        0 -> JsonNull
        1 -> JsonPrimitive(random.nextBoolean())
        2 -> JsonPrimitive(random.nextLong())
        3 -> JsonPrimitive(random.nextDouble(-1e300, 1e300))
        4 -> JsonPrimitive(text(random, 12))
        5 -> JsonPrimitive(types.random(random))
        6 -> JsonPrimitive(if (random.nextBoolean()) "true" else "false")
        7 -> JsonArray(List(random.nextInt(4)) { element(random, depth - 1) })
        else -> JsonObject(objectOf(random, depth - 1))
    }

    private fun objectOf(random: Random, depth: Int): Map<String, JsonElement> =
        List(random.nextInt(4)) { text(random, 6) to element(random, depth) }.toMap()

    private fun text(random: Random, max: Int): String = buildString {
        repeat(random.nextInt(max + 1)) { append(chars[random.nextInt(chars.length)]) }
    }

    private fun corrupt(text: String, random: Random): String {
        if (text.isEmpty()) return text
        val at = random.nextInt(text.length)
        return when (random.nextInt(3)) {
            0 -> text.substring(0, at)
            1 -> text.removeRange(at, at + 1)
            else -> text.substring(0, at) + chars[random.nextInt(chars.length)] + text.substring(at)
        }
    }
}
