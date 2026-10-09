package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.protocol.Inbound
import io.github.vad4nus.actioncable.internal.protocol.decodeInbound
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CableDecodeTest {

    @Test
    fun welcomeDecodesToWelcome() {
        val result = decodeInbound("""{"type":"welcome"}""")
        assertIs<Inbound.Welcome>(result)
    }

    @Test
    fun pingDecodesToPing() {
        val result = decodeInbound("""{"type":"ping","message":1234567890}""")
        assertIs<Inbound.Ping>(result)
    }

    @Test
    fun confirmSubscriptionDecodesToConfirm() {
        val result = decodeInbound("""{"type":"confirm_subscription","identifier":"{\"channel\":\"RoomChannel\"}"}""")
        val confirm = assertIs<Inbound.Confirm>(result)
        assertEquals("{\"channel\":\"RoomChannel\"}", confirm.identifier)
    }

    @Test
    fun rejectSubscriptionDecodesToReject() {
        val result = decodeInbound("""{"type":"reject_subscription","identifier":"{\"channel\":\"BadChannel\"}"}""")
        val reject = assertIs<Inbound.Reject>(result)
        assertEquals("{\"channel\":\"BadChannel\"}", reject.identifier)
    }

    @Test
    fun disconnectDecodesToDisconnect() {
        val result = decodeInbound("""{"type":"disconnect","reason":"server_restart","reconnect":true}""")
        val d = assertIs<Inbound.Disconnect>(result)
        assertEquals("server_restart", d.reason)
        assertTrue(d.reconnect)
    }

    @Test
    fun unknownTypeDecodesToUnknown() {
        val result = decodeInbound("""{"type":"custom_type"}""")
        val u = assertIs<Inbound.Unknown>(result)
        assertEquals("custom_type", u.type)
    }

    @Test
    fun messageObjectDecodesToMessage() {
        val result = decodeInbound("""{"identifier":"{\"channel\":\"C\"}","message":{"key":"value"}}""")
        val msg = assertIs<Inbound.Message>(result)
        assertEquals("{\"channel\":\"C\"}", msg.identifier)
        assertIs<JsonObject>(msg.message)
    }

    @Test
    fun messageObjectWithoutDataDecodesToMessage() {
        val result = decodeInbound("""{"identifier":"{\"channel\":\"C\"}","message":{"other":"field"}}""")
        val msg = assertIs<Inbound.Message>(result)
        assertIs<JsonObject>(msg.message)
    }

    @Test
    fun messageArrayDecodesToMessage() {
        val result = decodeInbound("""{"identifier":"{\"channel\":\"C\"}","message":[1,2,3]}""")
        val msg = assertIs<Inbound.Message>(result)
        assertIs<JsonArray>(msg.message)
    }

    @Test
    fun messageStringDecodesToMessage() {
        val result = decodeInbound("""{"identifier":"{\"channel\":\"C\"}","message":"hello"}""")
        val msg = assertIs<Inbound.Message>(result)
        assertEquals(JsonPrimitive("hello"), msg.message)
    }

    @Test
    fun messageNumberDecodesToMessage() {
        val result = decodeInbound("""{"identifier":"{\"channel\":\"C\"}","message":42}""")
        val msg = assertIs<Inbound.Message>(result)
        assertEquals(JsonPrimitive(42), msg.message)
    }

    @Test
    fun messageBooleanDecodesToMessage() {
        val result = decodeInbound("""{"identifier":"{\"channel\":\"C\"}","message":true}""")
        val msg = assertIs<Inbound.Message>(result)
        assertEquals(JsonPrimitive(true), msg.message)
    }

    @Test
    fun messageNullDecodesToMessage() {
        val result = decodeInbound("""{"identifier":"{\"channel\":\"C\"}","message":null}""")
        val msg = assertIs<Inbound.Message>(result)
        assertEquals(JsonNull, msg.message)
    }

    @Test
    fun broadcastWithTypePingDecodesToMessageNotPing() {
        val result = decodeInbound("""{"identifier":"{\"channel\":\"C\"}","message":{"type":"ping","data":"stuff"}}""")
        val msg = assertIs<Inbound.Message>(result)
        assertIs<JsonObject>(msg.message)
    }

    @Test
    fun disconnectReconnectTrue() {
        val result = decodeInbound("""{"type":"disconnect","reason":"r","reconnect":true}""")
        val d = assertIs<Inbound.Disconnect>(result)
        assertTrue(d.reconnect)
    }

    @Test
    fun disconnectReconnectFalse() {
        val result = decodeInbound("""{"type":"disconnect","reason":"r","reconnect":false}""")
        val d = assertIs<Inbound.Disconnect>(result)
        assertEquals(false, d.reconnect)
    }

    @Test
    fun disconnectReconnectMissingDefaultsFalse() {
        val result = decodeInbound("""{"type":"disconnect","reason":"r"}""")
        val d = assertIs<Inbound.Disconnect>(result)
        assertEquals(false, d.reconnect)
    }

    @Test
    fun disconnectMissingReasonIsNull() {
        val result = decodeInbound("""{"type":"disconnect","reconnect":false}""")
        val d = assertIs<Inbound.Disconnect>(result)
        assertNull(d.reason)
    }

    @Test
    fun disconnectWithAStructuredReasonIsUnknown() {
        assertEquals(Inbound.Unknown("disconnect"), decodeInbound("""{"type":"disconnect","reason":{"code":4001},"reconnect":true}"""))
        assertEquals(Inbound.Unknown("disconnect"), decodeInbound("""{"type":"disconnect","reason":["unauthorized"]}"""))
    }

    @Test
    fun malformedJsonReturnsNull() {
        val result = decodeInbound("not json {")
        assertNull(result)
    }

    @Test
    fun nonObjectJsonReturnsNull() {
        val result = decodeInbound("""["welcome"]""")
        assertNull(result)
    }

    @Test
    fun identifierAsObjectReturnsUnknownOrNull() {
        val result = decodeInbound("""{"type":"confirm_subscription","identifier":{}}""")
        assertTrue(result == null || result is Inbound.Unknown)
    }

    @Test
    fun reconnectYesReturnsUnknown() {
        val result = decodeInbound("""{"type":"disconnect","reconnect":"yes"}""")
        assertTrue(result == null || result is Inbound.Unknown)
    }

    @Test
    fun reconnectAsTheStringTrueReturnsUnknown() {
        assertEquals(Inbound.Unknown("disconnect"), decodeInbound("""{"type":"disconnect","reconnect":"true"}"""))
    }

    @Test
    fun messagePreservesElementIdentically() {
        val json = """{"identifier":"{\"channel\":\"C\"}","message":{"a":1,"b":[2,3],"c":null}}"""
        val result = decodeInbound(json)
        val msg = assertIs<Inbound.Message>(result)
        val obj = assertIs<JsonObject>(msg.message)
        assertEquals(JsonPrimitive(1), obj["a"])
        assertIs<JsonArray>(obj["b"])
        assertEquals(JsonNull, obj["c"])
    }
}
