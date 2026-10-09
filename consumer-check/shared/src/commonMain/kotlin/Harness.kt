package io.github.vad4nus.actioncable.check

import io.github.vad4nus.actioncable.ActionCableClient
import io.github.vad4nus.actioncable.CableLog
import io.github.vad4nus.actioncable.CableLogger
import io.github.vad4nus.actioncable.CableState
import io.github.vad4nus.actioncable.CableSubscription
import io.github.vad4nus.actioncable.SubscriptionState
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

class Harness(url: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val foreground = MutableStateFlow(true)
    private val online = MutableStateFlow(true)
    private val room = MutableStateFlow<CableSubscription?>(null)
    private val echoes = MutableStateFlow(0)

    init {
        CableLog.logger = CableLogger { level, message ->
            if (level != CableLogger.Level.DEBUG) println("actioncable $level $message")
        }
    }

    private val client: ActionCableClient = ActionCableClient(
        url = url,
        onRequest = { header(HttpHeaders.Origin, "http://localhost:3000") },
        onUnauthorized = { false },
        canConnect = combine(foreground, online) { visible, reachable -> visible && reachable },
        options = {
            staleTimeout = 15.seconds
            minReconnectDelay = 1.seconds
            maxReconnectDelay = 30.seconds
            maxReconnectAttempts = Int.MAX_VALUE
            messageBufferSize = 128
        },
    )

    val state: StateFlow<CableState> = client.state
    val subscription: StateFlow<CableSubscription?> = room.asStateFlow()

    fun start() {
        client.connect()
        scope.launch {
            val handle = client.subscribe(
                buildJsonObject {
                    put("channel", "EchoChannel")
                    put("room", "harness")
                },
            )
            room.value = handle
            handle.messages.collect { echoes.update { it + 1 } }
        }
    }

    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    fun setOnline(value: Boolean) {
        online.value = value
    }

    fun pause() = client.disconnect()

    suspend fun echo(text: String): Boolean =
        room.value?.perform("echo", buildJsonObject { put("payload", text) }) ?: false

    fun describe(): String {
        val handle = room.value
        val subscribed = handle?.state?.value ?: SubscriptionState.Pending
        return "${state.value}\n${handle?.identifier ?: "-"}\n$subscribed\nechoes: ${echoes.value}"
    }

    fun close() {
        room.value?.unsubscribe()
        scope.cancel()
        client.close()
    }
}
