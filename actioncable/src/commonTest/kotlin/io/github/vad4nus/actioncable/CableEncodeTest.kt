package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.protocol.encodePerform
import io.github.vad4nus.actioncable.internal.protocol.encodeSubscribe
import io.github.vad4nus.actioncable.internal.protocol.encodeUnsubscribe
import io.github.vad4nus.actioncable.internal.protocol.identifierOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CableEncodeTest {

    @Test
    fun encodeSubscribeFormat() {
        val id = "{\"channel\":\"RoomChannel\"}"
        val result = encodeSubscribe(id)
        assertEquals("""{"command":"subscribe","identifier":"{\"channel\":\"RoomChannel\"}"}""", result)
    }

    @Test
    fun encodeUnsubscribeFormat() {
        val id = "{\"channel\":\"RoomChannel\"}"
        val result = encodeUnsubscribe(id)
        assertEquals("""{"command":"unsubscribe","identifier":"{\"channel\":\"RoomChannel\"}"}""", result)
    }

    @Test
    fun encodePerformActionOverwritesExistingAction() {
        val id = "{\"channel\":\"C\"}"
        val data = buildJsonObject {
            put("body", "hi")
            put("action", "x")
        }
        val result = encodePerform(id, "speak", data)
        assertEquals(
            """{"command":"message","identifier":"{\"channel\":\"C\"}","data":"{\"body\":\"hi\",\"action\":\"speak\"}"}""",
            result
        )
    }

    @Test
    fun identifierOfMissingChannelThrowsIllegalArgument() {
        val obj = buildJsonObject { put("room_id", 1) }
        val ex = assertFailsWith<IllegalArgumentException> { identifierOf(obj) }
        assertTrue(ex.message?.startsWith("missing_channel") == true)
        assertNull(ex.cause)
    }

    @Test
    fun identifierOfNumericChannelThrowsIllegalArgument() {
        val obj = buildJsonObject { put("channel", 42) }
        val ex = assertFailsWith<IllegalArgumentException> { identifierOf(obj) }
        assertTrue(ex.message?.startsWith("missing_channel") == true)
        assertNull(ex.cause)
    }

    @Test
    fun identifierOfNanValueThrowsUnencodableData() {
        val obj = buildJsonObject {
            put("channel", "RoomChannel")
            put("x", Double.NaN)
        }
        val ex = assertFailsWith<IllegalArgumentException> { identifierOf(obj) }
        assertTrue(ex.message?.startsWith("unencodable_data") == true, "message was: ${ex.message}")
        assertNull(ex.cause)
    }

    @Test
    fun identifierOfInfinityThrowsUnencodableData() {
        val obj = buildJsonObject {
            put("channel", "RoomChannel")
            put("x", Double.POSITIVE_INFINITY)
        }
        val ex = assertFailsWith<IllegalArgumentException> { identifierOf(obj) }
        assertTrue(ex.message?.startsWith("unencodable_data") == true)
        assertNull(ex.cause)
    }

    @Test
    fun identifierOfSameObjectReturnsSameString() {
        val obj = buildJsonObject {
            put("channel", "RoomChannel")
            put("room_id", 42)
        }
        assertEquals(identifierOf(obj), identifierOf(obj))
    }

    @Test
    fun identifierOfPreservesKeyOrder() {
        val obj = buildJsonObject {
            put("channel", "RoomChannel")
            put("room_id", 42)
            put("extra", "value")
        }
        val result = identifierOf(obj)
        assertTrue(result.indexOf("channel") < result.indexOf("room_id"))
        assertTrue(result.indexOf("room_id") < result.indexOf("extra"))
    }

    @Test
    fun encodePerformNanDataThrowsUnencodableData() {
        val id = "{\"channel\":\"C\"}"
        val data = buildJsonObject { put("x", Double.NaN) }
        val ex = assertFailsWith<IllegalArgumentException> { encodePerform(id, "act", data) }
        assertTrue(ex.message?.startsWith("unencodable_data") == true)
        assertNull(ex.cause)
    }

    @Test
    fun encodePerformDoesNotSendWhenThrows() {
        val id = "{\"channel\":\"C\"}"
        val data = buildJsonObject { put("x", Double.NaN) }
        runCatching { encodePerform(id, "act", data) }
        val valid = encodePerform(id, "act", buildJsonObject { put("x", "ok") })
        assertTrue(valid.contains("\"command\":\"message\""))
    }
}
