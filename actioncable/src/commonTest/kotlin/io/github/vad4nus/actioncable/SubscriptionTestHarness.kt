package io.github.vad4nus.actioncable

import io.github.vad4nus.actioncable.internal.connection.CableConnection
import io.github.vad4nus.actioncable.internal.protocol.encodeSubscribe
import io.github.vad4nus.actioncable.internal.protocol.encodeUnsubscribe
import io.github.vad4nus.actioncable.internal.subscription.CableSubscriptionImpl
import io.github.vad4nus.actioncable.internal.subscription.SubscriptionRegistry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random

internal class SubscriptionTestHarness(
    testScope: TestScope,
    options: CableOptions = CableOptions(),
    seed: Long = 42,
) {
    private val scheduler = testScope.testScheduler
    val server = FakeCableServer(timeSource = scheduler.timeSource)
    val log = RecordingCableLogger()

    init {
        CableLog.logger = log
    }

    private val dispatcher = StandardTestDispatcher(scheduler)
    private val rand = Random(seed)

    val conn: CableConnection = CableConnection(
        url = "ws://fake/cable",
        onRequest = {},
        onUnauthorized = { false },
        canConnect = flowOf(true),
        options = options,
        opener = server.asOpener(),
        controlDispatcher = dispatcher,
        callbackContext = dispatcher,
        random = rand,
        scope = testScope.backgroundScope,
        timeSource = scheduler.timeSource,
        onReady = { scope -> registry.onReady(scope) },
        onInbound = { inbound -> registry.onInbound(inbound) },
        onLost = { registry.onLost() },
    )

    val registry: SubscriptionRegistry = SubscriptionRegistry(
        options = options,
        random = rand,
        send = { conn.send(it) },
    )

    val state get() = conn.state

    suspend fun start() {
        conn.start()
        settle()
    }

    suspend fun stop() = conn.stop()

    suspend fun connect() {
        start()
        server.welcome()
        settle()
    }

    fun reconnect() {
        server.drop()
        settle()
        advance(2_000)
        server.welcome()
        settle()
    }

    fun settle() = scheduler.runCurrent()

    fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    fun subscribe(identifier: String): CableSubscriptionImpl = registry.subscribe(identifier)

    fun wire(): List<String> = server.lastSession().allClientSent()

    fun subscribes(identifier: String, frames: List<String> = wire()): Int =
        frames.count { it == encodeSubscribe(identifier) }

    fun unsubscribes(identifier: String, frames: List<String> = wire()): Int =
        frames.count { it == encodeUnsubscribe(identifier) }
}

internal fun subscriptionTest(
    options: CableOptions = CableOptions(),
    body: suspend TestScope.(SubscriptionTestHarness) -> Unit,
): TestResult = runTest {
    val h = SubscriptionTestHarness(this, options)
    try {
        body(h)
    } finally {
        h.stop()
    }
}
