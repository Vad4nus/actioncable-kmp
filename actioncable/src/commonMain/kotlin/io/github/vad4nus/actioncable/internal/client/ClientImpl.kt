package io.github.vad4nus.actioncable.internal.client

import io.github.vad4nus.actioncable.ActionCableClient
import io.github.vad4nus.actioncable.CableLog
import io.github.vad4nus.actioncable.CableOptions
import io.github.vad4nus.actioncable.CableState
import io.github.vad4nus.actioncable.CableSubscription
import io.github.vad4nus.actioncable.internal.CableErrors
import io.github.vad4nus.actioncable.internal.connection.CableConnection
import io.github.vad4nus.actioncable.internal.connection.SessionOpener
import io.github.vad4nus.actioncable.internal.log.LogEvent
import io.github.vad4nus.actioncable.internal.log.LogField
import io.github.vad4nus.actioncable.internal.log.LogRedaction
import io.github.vad4nus.actioncable.internal.protocol.identifierOf
import io.github.vad4nus.actioncable.internal.subscription.CableSubscriptionImpl
import io.github.vad4nus.actioncable.internal.subscription.SubscriptionRegistry
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.TimeSource

internal class ClientImpl(
    url: String,
    onRequest: suspend HttpRequestBuilder.() -> Unit,
    onUnauthorized: suspend () -> Boolean,
    canConnect: Flow<Boolean>,
    private val options: CableOptions,
    opener: SessionOpener,
    private val release: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    callbackContext: CoroutineContext = Dispatchers.Default,
    random: Random = Random.Default,
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : ActionCableClient {

    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, e ->
            CableLog.e(LogEvent.INTERNAL_ERROR, LogField.CLASS to LogRedaction.classChain(e))
        },
    )

    private val commands = Channel<ClientCommand>(Channel.UNLIMITED, onUndeliveredElement = ::answerClosed)

    private val registry: SubscriptionRegistry = SubscriptionRegistry(options, random, send = { connection.send(it) })

    private val connection: CableConnection = CableConnection(
        url = url,
        onRequest = onRequest,
        onUnauthorized = onUnauthorized,
        canConnect = canConnect,
        options = options,
        opener = opener,
        controlDispatcher = dispatcher,
        callbackContext = callbackContext,
        random = random,
        scope = scope,
        timeSource = timeSource,
        onReady = registry::onReady,
        onInbound = registry::onInbound,
        onLost = registry::onLost,
    )

    override val state: StateFlow<CableState> = connection.state.asStateFlow()

    init {
        // Single-thread control plane; move JSON decoding off it if a consumer measures a bottleneck
        scope.launch(dispatcher) { drain() }
    }

    override fun connect() {
        if (!commands.trySend(ClientCommand.Connect).isSuccess) CableLog.closedCall(ClientOperation.CONNECT)
    }

    override fun disconnect() {
        if (!commands.trySend(ClientCommand.Disconnect).isSuccess) CableLog.closedCall(ClientOperation.DISCONNECT)
    }

    override suspend fun subscribe(identifier: JsonObject): CableSubscription {
        val id = identifierOf(identifier)
        val reply = CompletableDeferred<Result<CableSubscription>>()
        if (!commands.trySend(ClientCommand.Subscribe(id, reply)).isSuccess) {
            CableLog.closedCall(ClientOperation.SUBSCRIBE)
            return closedHandle(id)
        }
        val result = try {
            reply.await()
        } catch (e: CancellationException) {
            reply.cancel()
            if (!reply.isCancelled) reply.getCompleted().getOrNull()?.unsubscribe()
            throw e
        }
        return result.getOrThrow()
    }

    override fun close() {
        if (!connection.markClosed()) return CableLog.closedCall(ClientOperation.CLOSE)
        commands.close()
        CoroutineScope(dispatcher).launch {
            withContext(NonCancellable) {
                scope.cancel()
                commands.cancel()
                scope.coroutineContext.job.join()
                registry.closeAll()
                try {
                    release()
                } catch (e: Exception) {
                    CableLog.w(LogEvent.RELEASE_FAILED, LogField.CLASS to LogRedaction.classChain(e))
                }
            }
        }
    }

    private suspend fun drain() {
        for (command in commands) {
            if (state.value is CableState.Closed) {
                answerClosed(command)
                continue
            }
            try {
                apply(command)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                CableLog.e(LogEvent.COMMAND_FAILED, LogField.CLASS to LogRedaction.classChain(e))
                failed(command)
            }
        }
    }

    private suspend fun apply(command: ClientCommand) {
        when (command) {
            ClientCommand.Connect -> connection.start()
            ClientCommand.Disconnect -> connection.stop()
            is ClientCommand.Unsubscribe -> registry.unsubscribe(command.handle)
            is ClientCommand.Perform -> if (!command.reply.isCompleted) {
                command.reply.complete(Result.success(command.handle.performNow(command.frame)))
            }
            is ClientCommand.Subscribe -> if (!command.reply.isCompleted) {
                val handle = try {
                    registry.create(command.identifier)
                } catch (e: IllegalStateException) {
                    command.reply.complete(Result.failure(e))
                    return
                }
                if (command.reply.complete(Result.success(SubscriptionHandle(handle, commands)))) registry.register(handle)
            }
        }
    }

    private fun answerClosed(command: ClientCommand) {
        when (command) {
            is ClientCommand.Subscribe -> command.reply.complete(Result.success(closedHandle(command.identifier)))
            is ClientCommand.Perform -> command.reply.complete(Result.success(false))
            else -> Unit
        }
    }

    private fun failed(command: ClientCommand) {
        val e = CableErrors.commandFailed()
        when (command) {
            is ClientCommand.Subscribe -> command.reply.complete(Result.failure(e))
            is ClientCommand.Perform -> command.reply.complete(Result.failure(e))
            else -> Unit
        }
    }

    private fun closedHandle(identifier: String): CableSubscription = SubscriptionHandle(
        CableSubscriptionImpl(
            identifier = identifier,
            messageBufferSize = options.messageBufferSize,
            serialNumber = 0,
            send = { false },
            removeFromRegistry = {},
        ).apply { markUnsubscribed() },
        commands,
    )
}
