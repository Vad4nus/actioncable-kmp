package io.github.vad4nus.actioncable.check

import io.github.vad4nus.actioncable.CableState
import io.github.vad4nus.actioncable.SubscriptionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class HarnessTest {

    @Test
    fun createSubscribeClose() = runTest {
        withContext(Dispatchers.Default) {
            val harness = Harness("ws://localhost:9/cable")
            harness.start()
            val handle = withTimeout(5.seconds) { harness.subscription.filterNotNull().first() }
            assertEquals(SubscriptionState.Pending, handle.state.value)

            harness.close()

            assertEquals(CableState.Closed, harness.state.value)
            withTimeout(5.seconds) { handle.state.first { it == SubscriptionState.Unsubscribed } }
        }
    }
}
