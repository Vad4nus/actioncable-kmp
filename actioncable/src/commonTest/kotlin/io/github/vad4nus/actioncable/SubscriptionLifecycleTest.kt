package io.github.vad4nus.actioncable

import kotlinx.coroutines.test.currentTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class SubscriptionLifecycleTest {

    private val idA = """{"channel":"A"}"""
    private val idB = """{"channel":"B"}"""
    private val quiet = CableOptions().apply { staleTimeout = 10.minutes }

    @Test
    fun subscribeAfterConnectedSendsImmediately() = subscriptionTest { h ->
        h.connect()
        assertEquals(CableState.Connected, h.state.value)

        h.subscribe(idA)
        h.settle()

        assertEquals(1, h.subscribes(idA))
        assertEquals(0L, currentTime)
    }

    @Test
    fun duplicateIdentifierThrowsSendsNothingAndIsFreeAfterUnsubscribe() = subscriptionTest { h ->
        h.connect()
        val first = h.subscribe(idA)
        h.settle()
        val framesBefore = h.wire().size

        val ex = assertFailsWith<IllegalStateException> { h.subscribe(idA) }
        val message = ex.message.orEmpty()
        assertTrue(message.startsWith("duplicate_identifier"))
        assertFalse(message.contains("channel"))
        assertFalse(message.contains(idA))
        h.settle()
        assertEquals(framesBefore, h.wire().size)

        first.unsubscribe()
        val second = h.subscribe(idA)
        h.settle()
        assertNotSame(first, second)
        assertEquals(SubscriptionState.Pending, second.state.value)
        assertEquals(2, h.subscribes(idA))
    }

    @Test
    fun repeatedUnsubscribeOfAnOldHandleLeavesTheNewHandleRegistered() = subscriptionTest(quiet) { h ->
        h.connect()
        val first = h.subscribe(idA)
        h.settle()
        first.unsubscribe()
        val second = h.subscribe(idA)
        h.settle()

        first.unsubscribe()
        h.settle()
        h.server.confirm(idA)
        h.settle()

        assertEquals(SubscriptionState.Subscribed(1), second.state.value)
        assertEquals(1, h.unsubscribes(idA))
        assertFailsWith<IllegalStateException> { h.subscribe(idA) }
    }

    @Test
    fun unsubscribeDuringInFlightSubscribeIsNeverResubscribed() = subscriptionTest(quiet) { h ->
        h.connect()
        val sub = h.subscribe(idA)
        h.settle()
        assertEquals(SubscriptionState.Pending, sub.state.value)

        sub.unsubscribe()
        h.settle()
        assertEquals(SubscriptionState.Unsubscribed, sub.state.value)
        assertEquals(1, h.unsubscribes(idA))

        h.advance(120_000)
        assertEquals(1, h.subscribes(idA))

        h.reconnect()
        assertEquals(2, h.server.sessions().size)
        assertEquals(0, h.subscribes(idA))
    }

    @Test
    fun unsubscribeDuringReconnectBackoffIsNeverResubscribed() = subscriptionTest(quiet) { h ->
        h.connect()
        val sub = h.subscribe(idA)
        h.settle()
        h.server.confirm(idA)
        h.settle()

        h.server.drop()
        h.settle()
        assertEquals(SubscriptionState.Pending, sub.state.value)

        sub.unsubscribe()
        h.settle()
        assertEquals(SubscriptionState.Unsubscribed, sub.state.value)

        h.advance(2_000)
        h.server.welcome()
        h.settle()
        assertEquals(2, h.server.sessions().size)
        assertEquals(CableState.Connected, h.state.value)
        assertEquals(0, h.subscribes(idA))
        assertEquals(0, h.unsubscribes(idA))
        assertEquals(0, h.unsubscribes(idA, h.server.sessions().first().allClientSent()))
    }

    @Test
    fun unsubscribeAcrossStopAndStartIsNeverResubscribed() = subscriptionTest(quiet) { h ->
        h.connect()
        val sub = h.subscribe(idA)
        h.settle()

        h.stop()
        assertEquals(CableState.Disconnected, h.state.value)
        assertEquals(SubscriptionState.Pending, sub.state.value)

        sub.unsubscribe()
        assertEquals(SubscriptionState.Unsubscribed, sub.state.value)

        h.connect()
        assertEquals(2, h.server.sessions().size)
        assertEquals(0, h.subscribes(idA))
        assertEquals(0, h.unsubscribes(idA, h.server.sessions().first().allClientSent()))
    }

    @Test
    fun connectionLossCancelsPendingWaitAtOnceAndNextWelcomeResubscribes() = subscriptionTest(quiet) { h ->
        h.connect()
        val sub = h.subscribe(idA)
        h.settle()
        val wait = assertNotNull(sub.loopJob)
        assertTrue(wait.isActive)

        h.server.drop()
        h.settle()
        assertTrue(wait.isCancelled)
        assertEquals(SubscriptionState.Pending, sub.state.value)

        h.advance(2_000)
        h.server.welcome()
        h.settle()
        assertEquals(2, h.server.sessions().size)
        assertEquals(1, h.subscribes(idA))
    }

    @Test
    fun confirmationsGrowOncePerConnectionEvenWithDuplicateConfirms() = subscriptionTest(quiet) { h ->
        h.connect()
        val sub = h.subscribe(idA)
        h.settle()
        h.server.confirm(idA)
        h.server.confirm(idA)
        h.settle()
        assertEquals(SubscriptionState.Subscribed(1), sub.state.value)

        h.reconnect()
        assertEquals(SubscriptionState.Pending, sub.state.value)
        assertEquals(1, h.subscribes(idA))

        h.server.confirm(idA)
        h.server.confirm(idA)
        h.settle()
        assertEquals(SubscriptionState.Subscribed(2), sub.state.value)
    }

    @Test
    fun confirmAfterRejectOnSameConnectionSubscribesAndGrowsAtMostOnce() = subscriptionTest(quiet) { h ->
        h.connect()
        val a = h.subscribe(idA)
        val b = h.subscribe(idB)
        h.settle()

        h.server.confirm(idA)
        h.server.reject(idA)
        h.server.reject(idB)
        h.settle()
        assertEquals(SubscriptionState.Rejected, a.state.value)
        assertEquals(SubscriptionState.Rejected, b.state.value)

        h.server.confirm(idA)
        h.server.confirm(idB)
        h.settle()
        assertEquals(SubscriptionState.Subscribed(1), a.state.value)
        assertEquals(SubscriptionState.Subscribed(1), b.state.value)
    }

    @Test
    fun unsubscribeWhileDisconnectedSendsNothingAndSetsUnsubscribed() = subscriptionTest(quiet) { h ->
        val sub = h.subscribe(idA)
        assertEquals(SubscriptionState.Pending, sub.state.value)

        sub.unsubscribe()
        assertEquals(SubscriptionState.Unsubscribed, sub.state.value)
        assertEquals(0, h.server.calls)

        h.connect()
        assertTrue(h.wire().isEmpty())
    }
}
