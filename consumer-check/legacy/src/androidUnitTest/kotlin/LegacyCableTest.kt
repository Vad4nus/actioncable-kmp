package io.github.vad4nus.actioncable.check.legacy

import io.github.vad4nus.actioncable.CableState
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyCableTest {

    @Test
    fun clientResolvesFromAFlavoredAndroidTargetModule() {
        val client = legacyClient("ws://localhost:3000/cable")
        assertEquals(CableState.Disconnected, client.state.value)
        client.close()
    }
}
