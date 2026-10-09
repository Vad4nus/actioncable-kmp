# actioncable-kmp

Kotlin Multiplatform [Action Cable](https://guides.rubyonrails.org/action_cable_overview.html) client for Android and iOS, built on Ktor.

- One WebSocket connection per client, any number of channel subscriptions over it.
- Reconnects with exponential backoff and jitter, replaces stale sockets, and resubscribes after every reconnect.
- Thread-safe API that applies operations in call order; log lines carry no secrets.

## Installation

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.vad4nus:actioncable-kmp:0.1.0")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.0.3")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.0.3")
        }
    }
}
```

The library brings no Ktor engine. Add one per platform at the Ktor version your app already uses (3.0.3 or newer).

## Quick start

```kotlin
val client = ActionCableClient(
    url = "wss://example.com/cable",
    onRequest = {
        headers[HttpHeaders.Origin] = "https://example.com"
        headers[HttpHeaders.Authorization] = "Bearer ${tokens.cached()}"
    },
    onUnauthorized = { tokens.refresh() },
    canConnect = isForegroundAndOnline,
)
client.connect()

scope.launch {
    val room = client.subscribe(buildJsonObject {
        put("channel", "RoomChannel")
        put("room_id", 42)
    })
    try {
        launch { room.state.collect { render(it) } }
        launch { room.messages.collect { handle(it) } }
        val receipt = room.state.first { it is SubscriptionState.Subscribed || it is SubscriptionState.Rejected }
        if (receipt is SubscriptionState.Subscribed) {
            room.perform("speak", buildJsonObject { put("body", "Hello") })
        }
        awaitCancellation()
    } finally {
        room.unsubscribe()
    }
}
```

`tokens`, `isForegroundAndOnline`, `scope`, `render` and `handle` stand for your own code. A subscription outlives the coroutine that created it, so unsubscribe in `finally`. Call `client.close()` when the client is no longer needed.

## Examples

### Connect only when it makes sense

```kotlin
val canConnect = combine(isLoggedIn, isOnline, isForeground) { loggedIn, online, foreground ->
    loggedIn && online && foreground
}
val client = ActionCableClient(url = "wss://example.com/cable", canConnect = canConnect)
```

The client stays `Disconnected` while the flow is false and connects at once when it turns true. Pass a flow with a current value, such as a `StateFlow`.

### Authentication

| Need | How |
|------|-----|
| Origin-checked server (Rails by default) | `onRequest = { headers[HttpHeaders.Origin] = "https://example.com" }` |
| Query token | `onRequest = { parameter("token", tokens.cached()) }` |
| Header token | `onRequest = { headers[HttpHeaders.Authorization] = "Bearer ${tokens.cached()}" }` |
| Cookie session | `onRequest = { headers[HttpHeaders.Cookie] = session.cookie() }` |
| Token refresh | `onUnauthorized = { tokens.refresh() }` |

`onRequest` runs before every connection attempt. `onUnauthorized` runs when the server rejects the connection: return `true` to reconnect with refreshed credentials, `false` to stop, for example after logout.

### Connection banner

```kotlin
client.state.collect { state ->
    when (state) {
        CableState.Connected -> banner.hide()
        is CableState.Connecting -> if (state.attempt > 0) banner.showUnstable()
        is CableState.Stopped -> banner.showOffline()
        CableState.Disconnected, CableState.Closed -> Unit
    }
}
```

### Re-sync after a reconnect

```kotlin
room.state.collect { state ->
    if (state is SubscriptionState.Subscribed && state.confirmations > 1) reloadFromApi()
}
```

`confirmations > 1` means the subscription was restored after a reconnect, and messages may have been missed while the connection was down.

### Send an action

```kotlin
val queued = room.perform("speak", buildJsonObject { put("body", "Hello") })
```

`perform` returns `false` while the subscription is not confirmed or the client is offline. Nothing is queued offline; retry after the next `Subscribed`.

### Options

```kotlin
val client = ActionCableClient(
    url = "wss://example.com/cable",
    options = {
        staleTimeout = 30.seconds
        maxReconnectAttempts = 5
    },
)
```

| Option | Default | Meaning |
|--------|---------|---------|
| `staleTimeout` | 15 s | handshake deadline and silence limit |
| `minReconnectDelay` | 1 s | first backoff step |
| `maxReconnectDelay` | 30 s | backoff ceiling |
| `maxReconnectAttempts` | `Int.MAX_VALUE` | retries after the first attempt |
| `messageBufferSize` | 128 | buffered messages per subscription |

### Ktor configuration

```kotlin
val client = ActionCableClient(url = "wss://example.com/cable") {
    install(HttpCookies)
}
```

The typed factory takes the engine explicitly, in `androidMain` or `iosMain`:

```kotlin
val client = ActionCableClient(url = "wss://example.com/cable", engine = OkHttp) {
    engine {
        preconfigured = sharedOkHttp.newBuilder()
            .dispatcher(Dispatcher())
            .build()
    }
}
```

`close()` shuts down the dispatcher of a preconfigured OkHttp client, so derive it with a fresh `Dispatcher()` as above. Never share a preconfigured `NSURLSession`.

### iOS

The library ships klibs. Use it from your shared module and export that module to Swift, keeping the client behind a class of your own:

```kotlin
class Cable(url: String) {
    private val foreground = MutableStateFlow(true)
    private val client = ActionCableClient(url = url, canConnect = foreground)

    val state: StateFlow<CableState> = client.state

    fun start() = client.connect()

    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    fun close() = client.close()
}
```

```swift
.onChange(of: scenePhase) { _, phase in
    cable.setForeground(value: phase == .active)
}
```

`consumer-check/` holds a complete example: a shared module, a SwiftUI app and an Android app.

### Logging

```kotlin
if (BuildConfig.DEBUG) CableLog.logger = CableLogger.Platform
```

The logger is silent by default. `CableLogger.Platform` writes to Logcat with the tag `ActionCable` and to `NSLog` on iOS. Lines never contain headers, URL paths or queries, or payloads, so `CableLogger { level, message -> ... }` can forward them to a crash reporter.

## Compatibility

| | Minimum |
|-|---------|
| Kotlin | 2.1 |
| Ktor | 3.0.3 |
| Android | API 21, OkHttp engine |
| iOS | 13, Darwin engine |
| Server | Rails 7.1 to 8.1, AnyCable 1.6 |

On iOS, Ktor 3.3 needs Kotlin 2.2 or newer and Ktor 3.4 and newer need Kotlin 2.3 or newer.

## Contributing

Building, testing and releasing: [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache License 2.0, see [LICENSE](LICENSE).
