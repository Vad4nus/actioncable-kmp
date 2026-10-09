package io.github.vad4nus.actioncable

import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class SharedOkHttpStressTest {

    @Test
    fun sharedClientStaysUsableAcrossFiveHundredLifecyclesWithTheDispatcherRecipe() = e2eTest(timeout = 900.seconds) {
        val shared = OkHttpClient()
        val refs = mutableListOf<() -> Any?>()
        repeat(500) {
            refs += lifecycle { released ->
                ActionCableClient(
                    url = CABLE_URL,
                    engine = OkHttp,
                    onRequest = { origin() },
                    httpClientConfig = {
                        engine { preconfigured = shared.newBuilder().dispatcher(Dispatcher()).build() }
                        install(releaseHook(released))
                    },
                )
            }
        }
        awaitConnections(0)

        assertFalse(shared.dispatcher.executorService.isShutdown)
        val code = withContext(Dispatchers.IO) {
            shared.newCall(Request.Builder().url("$CONTROL_URL/connections").build()).execute().use { it.code }
        }
        assertEquals(200, code)
        assertEquals(0, survivors(refs, timeout = 30.seconds))
    }
}
