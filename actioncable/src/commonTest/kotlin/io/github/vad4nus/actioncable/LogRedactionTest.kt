package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.protocol.identifierOf
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogRedactionTest {

    private fun s(n: Int) = "sntl-${n.toString().padStart(2, '0')}-q9"
    private val sentinels = (1..27).map(::s)

    private fun TestScope.fixture(
        log: RecordingCableLogger,
        url: String = "ws://fake/cable",
        onRequest: suspend HttpRequestBuilder.() -> Unit = {},
        onUnauthorized: suspend () -> Boolean = { false },
        canConnect: Flow<Boolean> = flowOf(true),
        onRelease: () -> Unit = {},
    ): ClientFixture {
        val control = StandardTestDispatcher(testScheduler)
        return ClientFixture(
            url = url,
            onRequest = onRequest,
            onUnauthorized = onUnauthorized,
            canConnect = canConnect,
            logger = log,
            dispatcher = control,
            callbackContext = control,
            random = Random(11),
            timeSource = testScheduler.timeSource,
            onRelease = onRelease,
        )
    }

    private suspend fun TestScope.subscribed(fx: ClientFixture, identifier: JsonObject): CableSubscription {
        val handle = async(Dispatchers.Unconfined) { fx.client.subscribe(identifier) }
        runCurrent()
        fx.server.confirm(identifierOf(identifier))
        runCurrent()
        return handle.await().also { assertEquals(SubscriptionState.Subscribed(1), it.state.value) }
    }

    private fun RecordingCableLogger.leaks(): List<String> = lines().filter { line -> sentinels.any { it in line } }

    private fun RecordingCableLogger.debugs(): List<String> =
        entries.filter { it.level == CableLogger.Level.DEBUG }.map { it.message }

    @Test
    fun sentinelsFromEveryInputStayOutOfTheLog() = runTest {
        val log = RecordingCableLogger()
        val a = fixture(
            log,
            url = "wss://a.test/cable?token=${s(1)}",
            onRequest = {
                headers[HttpHeaders.Authorization] = "Bearer ${s(4)}"
                headers[HttpHeaders.Cookie] = "s=${s(5)}"
                parameter("token", s(6))
            },
            onUnauthorized = { true },
        )
        val b = fixture(log, url = "wss://u:${s(2)}@b.test:8443/${s(3)}")

        a.client.connect()
        runCurrent()
        a.server.lastSession().serverSend("""{"type":"${s(11)}","token":"${s(12)}"}""")
        a.server.disconnect("unauthorized")
        runCurrent()
        assertEquals(2, a.server.calls)
        a.server.welcome()
        runCurrent()
        assertEquals(CableState.Connected, a.client.state.value)

        val signed = buildJsonObject {
            put("channel", "RoomChannel")
            put("signed_stream_name", s(7))
        }
        val named = buildJsonObject { put("channel", s(8)) }
        val first = subscribed(a, signed)
        val second = subscribed(a, named)
        val performed = async(Dispatchers.Unconfined) { first.perform("speak", buildJsonObject { put("token", s(9)) }) }
        runCurrent()
        assertTrue(performed.await())

        val payload = buildJsonObject { put("token", s(10)) }
        repeat(200) {
            a.server.broadcast(identifierOf(signed), payload)
            a.server.broadcast(identifierOf(named), payload)
        }
        val unknown = JsonPrimitive("""{"channel":"${s(13)}"}""")
        a.server.lastSession().serverSend("""{"identifier":$unknown,"message":{"x":"${s(13)}"}}""")
        runCurrent()
        val drained = async(Dispatchers.Unconfined) { first.messages.take(128).toList() }
        runCurrent()
        assertEquals(128, drained.await().size)

        a.server.disconnect("${s(14)} Bearer x")
        runCurrent()
        assertEquals(CableState.Stopped("${s(14)} Bearer x"), a.client.state.value)
        assertEquals(SubscriptionState.Pending, second.state.value)
        a.client.close()
        runCurrent()

        b.client.connect()
        runCurrent()
        b.server.welcome()
        runCurrent()
        b.server.disconnect("invalid_request")
        runCurrent()
        b.client.close()
        runCurrent()

        assertEquals(emptyList(), log.leaks())
        val lines = log.lines()
        assertTrue("buffer_overflow_open sub=#1 dropped=72" in log.debugs(), log.debugs().toString())
        assertTrue("buffer_overflow_open sub=#2 dropped=72" in log.debugs(), log.debugs().toString())
        assertTrue(lines.none { "channel" in it || "RoomChannel" in it })
        assertTrue("state host=a.test state=Stopped reason=other" in lines, lines.toString())
        assertTrue("state host=b.test:8443 state=Stopped reason=invalid_request" in lines, lines.toString())
        assertTrue("frame_ignored type=other" in log.debugs())
        assertTrue(lines.filter { "host=" in it }.all { "host=a.test " in "$it " || "host=b.test:8443 " in "$it " }, lines.toString())
    }

    @Test
    fun injectedFailuresLogOneLineWithTheirClass() = runTest {
        suspend fun failure(expected: String, scenario: suspend TestScope.(RecordingCableLogger) -> Unit) {
            val log = RecordingCableLogger()
            scenario(log)
            val code = expected.substringBefore(' ')
            val raised = log.entries.filter { it.level >= CableLogger.Level.WARN && it.message.startsWith("$code ") }
            assertEquals(listOf(expected), raised.map { it.message })
            assertEquals(emptyList(), log.leaks())
        }

        failure("handshake_failed host=fake attempt=0 class=ProtocolException") { log ->
            val fx = fixture(log)
            fx.server.failNext(1) { ProtocolException("Expected HTTP 101 response but was 404 wss://a.test/cable?token=${s(15)}") }
            fx.client.connect()
            runCurrent()
            fx.client.close()
            runCurrent()
        }
        failure("handshake_failed host=fake attempt=0 class=IOException") { log ->
            val fx = fixture(log)
            fx.server.failNext(1) { IOException("Error Domain=NSURLErrorDomain UserInfo={NSErrorFailingURLStringKey=wss://a.test/cable?token=${s(16)}}") }
            fx.client.connect()
            runCurrent()
            fx.client.close()
            runCurrent()
        }
        failure("request_callback_failed host=fake attempt=0 class=IllegalStateException<RuntimeException") { log ->
            val fx = fixture(log, onRequest = {
                throw IllegalStateException("Authorization: Bearer ${s(17)}", RuntimeException("""{"token":"${s(18)}"}"""))
            })
            fx.client.connect()
            runCurrent()
            fx.client.close()
            runCurrent()
        }
        failure("unauthorized_callback_failed host=fake attempt=0 class=IllegalStateException") { log ->
            val fx = fixture(log, onUnauthorized = { throw IllegalStateException(s(19)) })
            fx.client.connect()
            runCurrent()
            fx.server.disconnect("unauthorized")
            runCurrent()
            fx.client.close()
            runCurrent()
        }
        failure("gate_failed host=fake class=IllegalStateException") { log ->
            val fx = fixture(log, canConnect = flow { throw IllegalStateException(s(20)) })
            runCurrent()
            fx.client.close()
            runCurrent()
        }
        failure("release_failed class=IllegalStateException") { log ->
            val fx = fixture(log, onRelease = { throw IllegalStateException(s(21)) })
            fx.client.close()
            runCurrent()
            assertEquals(1, fx.releaseCount)
        }
        failure("session_failed host=fake attempt=0 class=IOException") { log ->
            val fx = fixture(log)
            fx.client.connect()
            runCurrent()
            fx.server.welcome()
            runCurrent()
            fx.server.lastSession().closeWithError(
                IOException("Error Domain=NSURLErrorDomain Code=-1005 UserInfo={NSErrorFailingURLStringKey=wss://a.test/cable?token=${s(26)}}"),
            )
            runCurrent()
            fx.client.close()
            runCurrent()
        }
    }

    @Test
    fun libraryExceptionsHaveNoCauseAndNoSentinel() = runTest {
        fun check(code: String, e: Throwable) {
            val message = e.message.orEmpty()
            assertNull(e.cause, message)
            assertTrue(message.startsWith("$code: "), message)
            assertTrue(sentinels.none { it in message }, message)
        }

        check("invalid_url", assertFailsWith<IllegalArgumentException> { ActionCableClient("https://a.test/cable?token=${s(22)}") })
        check("invalid_url", assertFailsWith<IllegalArgumentException> { ActionCableClient("wss://a.test:${s(23)}/cable") })

        val fx = fixture(RecordingCableLogger())
        try {
            fx.client.connect()
            runCurrent()
            fx.server.welcome()
            runCurrent()
            val signed = buildJsonObject {
                put("channel", "RoomChannel")
                put("signed_stream_name", s(7))
            }
            val handle = subscribed(fx, signed)

            check("missing_channel", assertFailsWith<IllegalArgumentException> { fx.client.subscribe(buildJsonObject { put("room", s(24)) }) })
            check("duplicate_identifier", assertFailsWith<IllegalStateException> { fx.client.subscribe(signed) })
            check("unencodable_data", assertFailsWith<IllegalArgumentException> {
                handle.perform("speak", buildJsonObject {
                    put("body", s(25))
                    put("x", Double.NaN)
                })
            })
            check("unencodable_data", assertFailsWith<IllegalArgumentException> {
                fx.client.subscribe(buildJsonObject {
                    put("channel", "RoomChannel")
                    put("k", s(27))
                    put("x", Double.NaN)
                })
            })
            val collector = launch(Dispatchers.Unconfined) { handle.messages.collect {} }
            check("concurrent_collection", assertFailsWith<IllegalStateException> { handle.messages.first() })
            collector.cancel()
        } finally {
            fx.client.close()
            runCurrent()
        }
    }

    @Test
    fun sentinelsShapedLikeValidValuesAreNotLoggedEither() = runTest {
        val log = RecordingCableLogger()
        val fx = fixture(log)
        fx.client.connect()
        runCurrent()
        fx.server.lastSession().serverSend("""{"type":"sntl_type_x"}""")
        fx.server.welcome()
        runCurrent()
        val identifier = buildJsonObject { put("channel", "SntlTokenAbc") }
        subscribed(fx, identifier)
        repeat(200) { fx.server.broadcast(identifierOf(identifier), buildJsonObject { put("n", it) }) }
        runCurrent()
        fx.server.disconnect("sntl_stop_x")
        runCurrent()
        assertEquals(CableState.Stopped("sntl_stop_x"), fx.client.state.value)
        fx.client.close()
        runCurrent()

        val lines = log.lines()
        assertTrue("frame_ignored type=other" in log.debugs(), log.debugs().toString())
        assertTrue(lines.any { "state=Stopped reason=other" in it }, lines.toString())
        assertTrue("buffer_overflow_open sub=#1 dropped=72" in log.debugs(), log.debugs().toString())
        assertTrue(lines.none { "SntlTokenAbc" in it || "sntl_type_x" in it || "sntl_stop_x" in it }, lines.toString())
    }
}

private class ProtocolException(message: String) : Exception(message)

private class IOException(message: String) : Exception(message)
