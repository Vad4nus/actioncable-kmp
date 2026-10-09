package io.github.vad4nus.actioncable

import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class PreconfiguredOkHttpE2ETest {

    @Test
    fun freshDispatcherKeepsTheSharedClientUsableAfterClose() = e2eTest {
        val shared = OkHttpClient()
        val released = released()
        val client = track(
            ActionCableClient(
                url = CABLE_URL,
                engine = OkHttp,
                onRequest = { origin() },
                httpClientConfig = {
                    engine { preconfigured = shared.newBuilder().dispatcher(Dispatcher()).build() }
                    install(releaseHook(released))
                },
            ),
        )
        client.connect()
        client.awaitState(CableState.Connected)
        client.close()
        within(5.seconds, "release") { released.await() }
        delay(2.seconds)

        assertFalse(shared.dispatcher.executorService.isShutdown)
        val code = CompletableDeferred<Int>()
        shared.newCall(Request.Builder().url("$CONTROL_URL/connections").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                code.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { code.complete(it.code) }
            }
        })
        assertEquals(200, within(5.seconds, "enqueued call") { code.await() })
    }
}
