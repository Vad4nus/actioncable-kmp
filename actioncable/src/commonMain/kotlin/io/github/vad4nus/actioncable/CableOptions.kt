package io.github.vad4nus.actioncable

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public class CableOptions {
    public var staleTimeout: Duration = 15.seconds
    public var minReconnectDelay: Duration = 1.seconds
    public var maxReconnectDelay: Duration = 30.seconds
    public var maxReconnectAttempts: Int = Int.MAX_VALUE
    public var messageBufferSize: Int = 128
}
