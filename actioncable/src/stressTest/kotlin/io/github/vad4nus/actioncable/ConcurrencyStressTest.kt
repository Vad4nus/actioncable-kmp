package io.github.vad4nus.actioncable

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val THREADS = 32
private const val SLOTS = 4
private val LOAD = 20.seconds
private val FIXED = List(4) { room("storm-fixed-$it") }

private class Tracked(val client: ActionCableClient, val subscription: CableSubscription, val churn: Boolean) {
    val checks = MutableStateFlow(emptySet<String>())
    val unsubscribed = MutableStateFlow(false)
}

class ConcurrencyStressTest {

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun thirtyTwoThreadsCallingEveryOperationLeaveConsistentStates() = e2eTest(timeout = 600.seconds) {
        val scope: CoroutineScope = this
        warmDefaultDispatcher()
        val baseline = liveThreads()
        val creating = Mutex()
        suspend fun create(): ActionCableClient = creating.withLock { client() }
        val slots = List(SLOTS) { MutableStateFlow(create()) }
        val handles = MutableStateFlow(emptyList<Tracked>())
        val collectors = MutableStateFlow(emptyList<Job>())
        val unexpected = MutableStateFlow(emptyList<String>())
        val counts = MutableStateFlow(emptyMap<String, Int>())
        fun count(key: String) = counts.update { it + (key to (it[key] ?: 0) + 1) }

        suspend fun subscribe(random: Random) {
            val client = slots.random(random).value
            val churn = random.nextBoolean()
            val identifier = if (churn) room("storm-${random.nextLong().toULong()}") else FIXED.random(random)
            val tracked = Tracked(client, client.subscribe(identifier), churn)
            val collector = scope.launch {
                tracked.subscription.messages.collect { message ->
                    val text = (message as? JsonPrimitive)?.contentOrNull
                    if (text != null && text.startsWith("check-")) tracked.checks.update { it + text }
                }
            }
            collectors.update { it + collector }
            handles.update { it + tracked }
        }

        suspend fun replace(random: Random) {
            val slot = slots.random(random)
            val old = slot.value
            val new = create()
            if (slot.compareAndSet(old, new)) old.close() else new.close()
        }

        suspend fun operate(random: Random, n: Int): String {
            val roll = random.nextInt(1000)
            when {
                roll < 10 -> slots.random(random).value.connect().also { return "connect" }
                roll < 15 -> slots.random(random).value.disconnect().also { return "disconnect" }
                roll < 17 -> replace(random).also { return "replace" }
                roll < 217 -> subscribe(random).also { return "subscribe" }
                roll < 367 -> {
                    val target = handles.value.filter { it.churn && !it.unsubscribed.value }.randomOrNull(random) ?: return "unsubscribe_none"
                    target.unsubscribed.value = true
                    target.subscription.unsubscribe()
                    return "unsubscribe"
                }
                else -> {
                    val target = handles.value.randomOrNull(random) ?: return "perform_none"
                    return if (target.subscription.perform("echo", echo(JsonPrimitive(n)))) "perform_true" else "perform_false"
                }
            }
        }

        val pool = newFixedThreadPoolContext(THREADS, "storm")
        val end = TimeSource.Monotonic.markNow() + LOAD
        try {
            List(THREADS) { worker ->
                launch(pool) {
                    val random = Random(worker)
                    var n = 0
                    while (end.hasNotPassedNow()) {
                        try {
                            count(withTimeout(10.seconds) { operate(random, n++) })
                        } catch (e: TimeoutCancellationException) {
                            unexpected.update { it + "hang: $e" }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IllegalStateException) {
                            if (e.message.orEmpty().startsWith("duplicate_identifier")) count("duplicate") else unexpected.update { it + "$e" }
                        } catch (e: Exception) {
                            unexpected.update { it + "$e" }
                        }
                        delay(random.nextLong(1, 10))
                    }
                }
            }.joinAll()
        } finally {
            pool.close()
        }

        val current = slots.map { it.value }
        current.forEach { it.connect() }
        current.forEach { it.awaitState(CableState.Connected, timeout = 30.seconds) }
        val all = handles.value
        all.forEachIndexed { index, tracked ->
            if (current.any { it === tracked.client } && !tracked.unsubscribed.value) {
                within(30.seconds, "subscription #$index subscribed") {
                    tracked.subscription.state.first { it is SubscriptionState.Subscribed }
                }
                assertTrue(tracked.subscription.perform("echo", echo(JsonPrimitive("check-$index"))))
                within(10.seconds, "echo on subscription #$index") { tracked.checks.first { "check-$index" in it } }
            } else {
                tracked.subscription.awaitState(SubscriptionState.Unsubscribed)
            }
        }
        for (client in current) {
            val fixed = all.filter { it.client === client && !it.churn }.groupBy { it.subscription.identifier }
            assertTrue(fixed.values.all { it.size == 1 }, "fixed identifiers subscribed twice: ${fixed.mapValues { it.value.size }}")
        }

        current.forEach { it.close() }
        all.forEach { it.subscription.awaitState(SubscriptionState.Unsubscribed) }
        within(10.seconds, "collectors completed") { collectors.value.joinAll() }
        awaitConnections(0)
        val settled = settledThreads(ceiling = baseline + 8)
        println("concurrency-stress ops=${counts.value.toList().sortedBy { it.first }.toMap()} handles=${all.size} baselineThreads=$baseline settledThreads=$settled")

        assertEquals(emptyList(), unexpected.value)
        assertTrue(settled <= baseline + 8, "settled=$settled baseline=$baseline")
    }
}
