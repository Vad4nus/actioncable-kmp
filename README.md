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

The library brings `ktor-client-core`, `kotlinx-coroutines-core` and `kotlinx-serialization-json` as `api` dependencies, and no engine. Add one engine per platform at the Ktor version your app already uses (3.0.3 or newer): OkHttp on Android, Darwin on iOS. Ktor finds it on the classpath, or you pass it explicitly (see [Engine configuration](#engine-configuration)).

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

`tokens`, `isForegroundAndOnline`, `scope`, `render` and `handle` stand for your own code. Call `client.close()` when the client is no longer needed: it closes the connection and releases the HttpClient.

## Connection

| State | Meaning |
|-------|---------|
| `Disconnected` | `connect()` not called yet, `disconnect()` called, or waiting for `canConnect` to turn true |
| `Connecting(attempt)` | opening a connection. `attempt >= 1` means retrying after a failure, the signal for a "connection unstable" banner |
| `Connected` | the server sent `welcome` |
| `Stopped(reason)` | the client gave up: the server sent `disconnect` with `reconnect: false`, authorization failed for good, or `maxReconnectAttempts` ran out (`"max_reconnect_attempts"`). Call `connect()` to start again |
| `Closed` | `close()` was called. Terminal |

- `connect()` arms the client: it connects whenever `canConnect` is true and keeps reconnecting until `disconnect()`, `Stopped` or `close()`. A call while armed does nothing.
- `disconnect()` closes the connection but keeps the subscriptions and the HttpClient; the next `connect()` resubscribes them.
- `close()` closes the connection and the HttpClient and completes every subscription as `Unsubscribed`. Later calls do nothing and log WARN `closed_call`.

Reconnect policy:
- A failed attempt (open error, `onRequest` error, no `welcome` within `staleTimeout`, socket closed before `welcome`) is retried after backoff(n) = min(`maxReconnectDelay`, `minReconnectDelay` x 2^(n-1)) ± 25 % jitter.
- A connection is healthy once it has lived 10 s after `welcome`, which resets the attempt counter. When the server closes a healthy connection, the first retry waits a random delay in [0, `minReconnectDelay`], so a server restart does not bring every client back at the same instant. A connection lost before it became healthy continues the backoff.
- Watchdog: once connected, no inbound frame for `staleTimeout` replaces the socket (Rails pings every 3 s). Before `welcome`, `staleTimeout` bounds the whole handshake: open, `onRequest` and `welcome`. A frame counts once it has fully arrived, so on a slow link a message that takes longer than about `staleTimeout` minus 3 s to download also replaces the socket.
- Retries are unbounded by default, as in the Rails JS client. `maxReconnectAttempts` limits the retries after the first attempt; time spent waiting for `canConnect` never counts.

Network faults, checked on both engines with `e2e-server/chaos.py`: a black-holed connection is replaced within `staleTimeout`; refused connections back off 1, 2, 4, 8, 16 and 30 s; after a one-minute server outage, 100 clients retried 6 or 7 times each and came back spread over 35 s; reset and cut connections count as losses; 800 ms of latency each way and a 4 KiB/s link keep the session. On iOS, `NSURLSession` reports a connection that the server closes in the middle of a message only after its 60 s request timeout, so there the watchdog replaces the socket after `staleTimeout`; OkHttp reports it at once.

| Option | Default | Meaning |
|--------|---------|---------|
| `staleTimeout` | 15 s | handshake deadline and silence limit |
| `minReconnectDelay` | 1 s | first backoff step |
| `maxReconnectDelay` | 30 s | backoff ceiling |
| `maxReconnectAttempts` | `Int.MAX_VALUE` | retries after the first attempt; 0 means no retries |
| `messageBufferSize` | 128 | buffered messages per subscription |

Invalid values (non-positive durations, min > max, negative attempts, buffer < 1, a URL that is not `ws` or `wss`) throw `IllegalArgumentException` at construction.

### canConnect

`canConnect` gates the connection. While it is false the client stays `Disconnected`, and it connects without delay once the flow turns true. Typical inputs are login state, network availability and app foreground:

```kotlin
val gate = combine(isLoggedIn, isOnline, isForeground) { loggedIn, online, foreground ->
    loggedIn && online && foreground
}
```

- Network availability is worth the input: on an Android emulator, with the gate fed by `ConnectivityManager.registerDefaultNetworkCallback`, the client reconnected 2 s after airplane mode ended; without it the client sat in a 30 s backoff and reconnected 31 s after. A Wi-Fi to mobile handover needs no gate: the old socket fails at once and the client reconnects within a second. `consumer-check/androidApp` shows the callback.
- Pass a flow with a current value (a `StateFlow`, or `onStart { emit(current) }`). The gate stays closed until the first emission, and the client logs INFO `gate_waiting` once while it waits.
- A flow that completes keeps its last value. A flow that throws is logged as ERROR `gate_failed`, keeps its last value and is collected again after a backoff.

## Authentication

| Need | How |
|------|-----|
| Origin-checked server (Rails by default) | `onRequest = { headers[HttpHeaders.Origin] = "https://example.com" }` |
| Query token | `onRequest = { parameter("token", tokens.cached()) }` |
| Header token | `onRequest = { headers[HttpHeaders.Authorization] = "Bearer ${tokens.cached()}" }` |
| Cookie session | `onRequest = { headers[HttpHeaders.Cookie] = session.cookie() }`, or `install(HttpCookies)` in `httpClientConfig` |
| Token refresh | `onUnauthorized = { tokens.refresh() }` |
| Waiting for credentials | `canConnect = isLoggedIn` |

- Rails rejects a WebSocket request without an allowed `Origin` header. By default it allows the cable host itself (`https://example.com` for `wss://example.com/cable`) plus `config.action_cable.allowed_request_origins`. A rejected origin gets HTTP 404 before the upgrade, which looks like a network failure: a growing `Connecting(attempt)` with WARN `handshake_failed` lines.
- `onRequest` runs before every attempt on a fresh request that already carries the URL and the `Sec-WebSocket-Protocol` header. `headers[name] = value` replaces a header, `header(name, value)` appends one.
- `onRequest` runs within `staleTimeout`, so read cached credentials there and refresh them in `onUnauthorized`. An exception from `onRequest` counts as a failed attempt.
- Neither engine follows a redirect of the upgrade request: it fails the attempt, so headers and cookies never reach the redirect target. Point the client at the final URL.
- Unauthorized means a `disconnect` frame with reason `unauthorized` (Rails `reject_unauthorized_connection`) or `token_expired` (AnyCable JWT). The client then calls `onUnauthorized` at most once per streak of unauthorized results:
  - `true`: reconnect at once with the refreshed credentials;
  - `false`: `Stopped(reason)`. Return false when the credentials are gone for good, for example after logout;
  - an exception: a failed attempt, retried after backoff, without limit by default. Do not throw to mean "logged out".
- A second unauthorized result in the same streak stops the client, even if the connection in between reached `welcome`. An unauthorized result that ends a healthy connection starts a new streak.

## Subscriptions

`subscribe(identifier)` returns a handle without waiting for the server. The identifier must contain a string `channel` key (otherwise `IllegalArgumentException` `missing_channel`), and the whole identifier becomes the channel's `params`. The client sends `subscribe` whenever it is connected and again after every reconnect.

| State | Meaning |
|-------|---------|
| `Pending` | waiting for the server's answer or for a connection |
| `Subscribed(confirmations)` | confirmed. `confirmations > 1` means the subscription was restored after a reconnect and messages may have been missed: re-sync through your own API |
| `Rejected` | the server rejected it; retried after the next reconnect |
| `Unsubscribed` | `unsubscribe()` or `close()` was called. Terminal |

- One live handle per identifier per client: subscribing an equal identifier again throws `IllegalStateException` `duplicate_identifier`. After `unsubscribe()` the identifier is free.
- `unsubscribe()` takes effect locally at once, with no server round trip.
- A `Pending` handle sends `subscribe` again if no answer arrives in 5, 10, 20, 30, 30... s (jittered, capped at `maxReconnectDelay`). Rails applies a connection's commands on a worker pool, possibly out of order, so the re-send starts late and never includes `unsubscribe`.
- A handle outlives the coroutine that created it until `unsubscribe()` or `close()`, so unsubscribe in `finally` as in the quick start. A screen that comes back subscribes again after `cancelAndJoin()` of its old coroutine, once the identifier is free.

Duplicate subscribe on the server: a re-send can reach a server that already applied the first `subscribe`. Rails 7.1 through 8.1 ignore the duplicate silently (checked on 7.1.6, 7.2.4 and 8.1.4: no reply, no log line); Rails main raises and logs `AlreadySubscribedError`. Neither replies, so the handle keeps the state of the first answer.

### Re-subscribing on a live connection

`unsubscribe()` followed by `subscribe()` with the same identifier on the same connection can leave the new handle `Subscribed` while the server holds no subscription until the next reconnect. Rails may apply the old `unsubscribe` after the new `subscribe`, and the protocol cannot tell the two confirmations apart; the Rails JS client has the same gap. The same applies after a `subscribe()` call that was cancelled. Add a nonce so the server sees a distinct subscription:

```kotlin
val room = client.subscribe(buildJsonObject {
    put("channel", "RoomChannel")
    put("room_id", 42)
    put("nonce", Random.nextLong())
})
```

## Messages

- `messages` emits the payload of every `message` frame for the subscription.
- Each subscription buffers up to `messageBufferSize` messages from the moment of `subscribe()`, so messages that arrive before you start collecting are kept.
- On overflow the oldest buffered message is dropped and the socket never waits. Each overflow episode logs one WARN `buffer_overflow` with `sub=#n` and the number dropped.
- `messages` has a single consumer: a second concurrent collection throws `IllegalStateException` `concurrent_collection`. A later collection resumes from the buffer; wait for the previous collector with `cancelAndJoin()` first. A message handed over at the moment its collector is cancelled is lost, so delivery is at most once across cancellation. Use `shareIn` for several consumers.
- After `Unsubscribed`, `messages` delivers what is buffered and then completes normally.
- Collect on a dispatching dispatcher. A collector of `messages` or `state` on `Dispatchers.Unconfined` resumes inline on the library's control plane and runs there until it suspends, so it must not block.

Inbound memory: only the number of buffered messages per subscription is bounded. The size of one message is limited by the engine alone (see [Message size](#message-size)), and the engine's own inbound queues are unbounded; the library drains them without waiting for your collectors. Log lines name a subscription only as `sub=#n`, its creation number within the client, in subscribe order.

## perform

```kotlin
val queued = room.perform("speak", buildJsonObject { put("body", "Hello") })
```

- Sends `{"command":"message","identifier":...,"data":"..."}`. The `action` argument overwrites `data["action"]`, as in the Rails JS client.
- Returns true only if the handle is `Subscribed` on the live connection and the frame was queued for it. True means queued, not written, delivered or processed: a frame still queued when the connection drops is discarded.
- Returns false when the handle is `Pending`, `Rejected` or `Unsubscribed`, or the client is not connected or closed. There is no offline queue and nothing is re-sent after a reconnect; retry in your own code, for example after the next `Subscribed`.
- No backpressure: `perform` never waits for the socket, and queued frames stay in memory until the connection writes or drops them.
- Rails may process consecutive `perform` calls at the same time, so their order on the server is not guaranteed.
- Data that JSON cannot encode (`Double.NaN`, infinities) throws `IllegalArgumentException` `unencodable_data`.

## Threading and callbacks

- Every public member is thread-safe and can be called from any thread, including the iOS main thread.
- Operations apply in call order. `connect`, `disconnect` and `unsubscribe` return immediately; `subscribe` and `perform` suspend until their command is applied, never on network I/O.
- `state` is a `StateFlow`, so a slow collector may skip intermediate values. `Subscribed.confirmations` keeps resubscriptions distinguishable.
- `onRequest`, `onUnauthorized` and the collection of `canConnect` run on `Dispatchers.Default`, never on the control plane. All clients share it, so keep callbacks non-blocking.
- Callbacks must honour cancellation: use suspending I/O in common code and wrap blocking calls in `runInterruptible(Dispatchers.IO) { ... }` on Android. A callback that ignores cancellation delays `connect`, `disconnect` and every operation queued behind them, and delays the HttpClient release on `close()`.
- `disconnect()`, `close()` and `canConnect` turning false cancel a running callback.
- The first client in a process initializes Ktor, whose `ServiceLoader` lookups read the APK on Android, with or without `engine`. On the main thread StrictMode reports it as a `DiskReadViolation`; create the client off the main thread if your policy forbids that.

## Engine configuration

The library builds and owns its HttpClient as `HttpClient { install(WebSockets); httpClientConfig() }`. Ktor chains repeated `install` blocks, so `install(WebSockets) { ... }` in `httpClientConfig` extends the library's configuration. The untyped factory works in common code:

```kotlin
val client = ActionCableClient(url = "wss://example.com/cable") {
    install(HttpCookies)
}
```

The typed factory takes the engine explicitly and makes its configuration reachable. The `engine { }` block lives in `androidMain` or `iosMain`:

```kotlin
val client = ActionCableClient(url = "wss://example.com/cable", engine = OkHttp) {
    engine {
        preconfigured = sharedOkHttp.newBuilder()
            .dispatcher(Dispatcher())
            .build()
    }
}
```

Preconfigured engines:
- OkHttp: derive the client from your shared one with a fresh `Dispatcher()`. `close()` closes the owned HttpClient, and Ktor's OkHttp engine then shuts down the dispatcher of the preconfigured client (checked on Ktor 3.5.2), so a shared client passed as is would stop working across the app. The engine also evicts the connection pool, which costs your next REST call a new connection; add `.connectionPool(ConnectionPool())` if that matters.
- OkHttp: keep a finite `readTimeout` (OkHttp's default is 10 s). OkHttp lets an upgrade in flight run until the server answers or `readTimeout` fires, so a stalled upgrade can outlive `disconnect()` or `close()` by that long.
- Darwin: a preconfigured `NSURLSession` is invalidated when the client closes, so never share one. Pin certificates with the engine's `handleChallenge` instead, and evaluate the server trust before comparing pins: a handler that answers `credentialForTrust` after only matching the leaf certificate accepted that certificate for another host name.
- A private CA on OkHttp: `engine { config { sslSocketFactory(factory, trustManager) } }` keeps Ktor's own OkHttpClient and OkHttp's host name check.

### Message size

- Darwin: the limit is `WebSockets.maxFrameSize`, default `Int.MAX_VALUE`. Ktor 3.4 and newer apply it to `NSURLSessionWebSocketTask` before the task starts, so you can lower it to cap inbound memory:

  ```kotlin
  val client = ActionCableClient(url = "wss://example.com/cable", engine = Darwin) {
      install(WebSockets) {
          maxFrameSize = 4L * 1024 * 1024
      }
  }
  ```

  Ktor 3.0.3 through 3.3.x apply it after the task has started, so `NSURLSession`'s 1 MB default stays in force and cannot be raised: a larger inbound message fails the connection, and the client reconnects.
- OkHttp: leave `maxFrameSize` at the default. OkHttp has no frame-size setting, and Ktor fails every handshake with `WebSocketException` for any other value.

## Several connections

Each `ActionCableClient(...)` call is one WebSocket connection with its own URL, callbacks, gate, options and HttpClient. The only library state clients share is the [logger](#logging). `disconnect()`, `close()`, a closed gate or `Stopped` on one client never affect another, and the same identifier may be subscribed on several clients.

One connection already multiplexes any number of channels. Use several clients when connections differ in server, credentials, gate or reconnect policy:

```kotlin
val chat = ActionCableClient(
    url = "wss://chat.example.com/cable",
    onRequest = { headers[HttpHeaders.Authorization] = "Bearer ${chatTokens.cached()}" },
    onUnauthorized = { chatTokens.refresh() },
    canConnect = isLoggedInAndOnline,
)
val market = ActionCableClient(
    url = "wss://market.example.com/cable",
    canConnect = isForegroundAndOnline,
    options = {
        staleTimeout = 30.seconds
        maxReconnectAttempts = 5
    },
)
```

Clients do share `Dispatchers.Default`, where callbacks run, so a blocking callback slows every client. They also share engine-wide resources: `close()` of any OkHttp-based client evicts idle connections from Ktor's shared default pool, each client with a preconfigured OkHttp client needs its own `Dispatcher()`, and a preconfigured Darwin session must never be shared.

## iOS

The library ships klibs, not a framework. Use it from your KMP shared module and export that module to Swift as usual, keeping the client behind a class of your own:

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

iOS suspends a backgrounded app together with its socket. With the `scenePhase` gate, the client disconnects on the way out and reconnects without delay on return; without it, the watchdog replaces the dead socket within `staleTimeout` after resume. `consumer-check/` holds a complete example: a shared module (`Harness.kt`), a SwiftUI app and an Android app.

## Logging

```kotlin
CableLog.logger = CableLogger { level, message -> println("cable $level $message") }
```

The logger is process-wide and silent by default (`CableLogger.None`). Set it once at startup, before the first client. `CableLogger.Platform` writes to Logcat with the tag `ActionCable` on Android and to `NSLog` on iOS, for example `if (BuildConfig.DEBUG) CableLog.logger = CableLogger.Platform`. Lines from several clients differ by `host=`.

Every line is built from a fixed set of fields, `<code> k=v ...`, for example `handshake_failed host=example.com attempt=3 class=ProtocolException`. Lines never contain exception messages, the URL's path or query, headers, identifiers, payloads or `perform` data, so they are safe to forward to a crash reporter.

- Public values stay raw: `CableSubscription.identifier` is your identifier JSON and `Stopped.reason` is the server's string. Filter both, and your own exceptions, before logging them.
- The library never installs Ktor `Logging`. If you install it in `httpClientConfig`, or enable TRACE for the `io.ktor` loggers, Ktor prints request URLs, including tokens passed as query parameters.
- Exceptions the library throws start with their code (`missing_channel: ...`), have no cause, and never contain the URL, the identifier or `perform` data.
- Every Darwin handshake failure logs as `class=DarwinHttpRequestException`, without the `NSURLError` code.

| Code | Level | When |
|------|-------|------|
| `gate_waiting` | INFO | `canConnect` has not emitted yet |
| `state` | INFO | the connection state changed |
| `handshake_failed` | WARN | opening failed, or no `welcome` within `staleTimeout` |
| `request_callback_failed` | WARN | `onRequest` threw |
| `unauthorized_callback_failed` | WARN | `onUnauthorized` threw or timed out |
| `session_failed` | WARN | an error in a live connection, handled as a loss |
| `gate_failed` | ERROR | `canConnect` threw |
| `buffer_overflow` | WARN | a subscription dropped messages |
| `buffer_overflow_open` | DEBUG | an overflow episode was still open at unsubscribe or close |
| `frame_ignored` | DEBUG | an unexpected or unroutable frame |
| `closed_call` | WARN | a call after `close()` |
| `command_failed` | ERROR | an operation failed internally |
| `internal_error` | ERROR | an unexpected internal error |
| `release_failed` | WARN | closing the HttpClient threw |

## Compatibility

| | Minimum | Tested |
|-|---------|--------|
| Kotlin | 2.1 | 2.1.21, 2.2.10, 2.3.20, 2.4.10 |
| Ktor | 3.0.3 | 3.0.3, 3.1.3, 3.2.3, 3.3.3, 3.4.0, 3.5.2 |
| kotlinx-coroutines | 1.9.0 | 1.9.0, 1.11.0 |
| kotlinx-serialization-json | 1.7.3 | 1.7.3, 1.11.0 |
| Android | API 21 | OkHttp engine; API 21 and 36 emulators, debug and R8-minified builds; `com.android.library` consumers with product flavors |
| iOS | 13 | Darwin engine; iosArm64, iosSimulatorArm64, iosX64; iOS 18.6 and 26.2 simulators |
| Server | Action Cable protocol `actioncable-v1-json` | Rails 7.1.6, 7.2.4 and 8.1.4 (`e2e-server/`); AnyCable 1.6.17 with JWT and `$pubsub` streams |

- On iOS, Ktor 3.3 needs Kotlin 2.2 or newer and Ktor 3.4 and newer need Kotlin 2.3 or newer, so choose the Ktor version your Kotlin version supports.
- Ktor 3.0 through 3.3 on Darwin sometimes leave the session's incoming channel open when the server closes the socket right after the upgrade, as Rails does when it rejects an unauthorized connection (about 1 in 150 such attempts under load). The client notices within a second, logs `handshake_failed` with `class=StaleTimeoutException` and retries; Ktor 3.4 fixed the cause.
- Ktor leaks when a WebSocket call is cancelled mid-handshake: OkHttp, through Ktor 3.3, blocks its reader thread for good along with the socket, and Darwin, through Ktor 3.5.2 at least, now and then leaves the socket open on the server. The client therefore never cancels a handshake: one in flight at `disconnect()`, `close()` or a stale timeout runs until the server answers or the engine gives up (OkHttp's `readTimeout`, 10 s by default; `NSURLSession`'s request timeout, 60 s), and the session it opens is closed at once.
- iosX64 has been a tier 3 Kotlin target since Kotlin 2.3; the library still publishes it.

## Development

```bash
./gradlew :actioncable:testAndroidHostTest
```

```bash
./gradlew :actioncable:iosSimulatorArm64Test
```

```bash
./gradlew apiCheck
```

- iOS tests run with `iosSimulatorArm64Test` on Apple Silicon and CI, and with `iosX64Test` on Intel Macs, here and in `consumer-check`. `-PiosSimulatorDevice="iPhone 16"` picks the simulator.
- Run `./gradlew apiDump` after an intended public API change.

End-to-end tests run against the reference Rails server in `e2e-server/` (Ruby 3.4). Start it:

```bash
cd e2e-server && bundle install && bin/rails server -p 3000 -b 0.0.0.0
```

Then add `-Pe2e=true` to the test tasks:

```bash
./gradlew :actioncable:testAndroidHostTest -Pe2e=true
```

```bash
./gradlew :actioncable:iosSimulatorArm64Test -Pe2e=true
```

The suites live in their own source sets (`e2eTest`, `e2eAndroidHostTest`, `e2eIosTest` and the matching `stress*` ones). An IDE sync always imports them, but a Gradle run includes them only with the flags, so add `-Pe2e=true` or `-Pstress=true` to an IDE run configuration too.

`-Pstress=true` adds the long-running suite on top of the end-to-end one: hundreds of client lifecycles checked for leaked clients, threads and server connections, a thousand immediate rejections, 32 threads calling every operation at once, and network faults. Select it with `--tests '*StressTest'`. The fault tests (`*ChaosStressTest`) need `e2e-server/chaos.py` in place of the plain server: it starts Rails on port 3000 itself, proxies port 3100 to it and, on command from the tests, black-holes, delays, throttles, refuses, resets or cuts connections and stops or starts Rails:

```bash
cd e2e-server && python3 chaos.py
```

`consumer-check/` builds against the published artifact the way an app does. Publish to `mavenLocal()` first:

```bash
./gradlew :actioncable:publishToMavenLocal
```

```bash
./gradlew -p consumer-check -Pmatrix=floor :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :androidApp:assembleDebug :legacy:test
```

```bash
./gradlew -p consumer-check -Pmatrix=latest -Pe2e=true :shared:testAndroidHostTest :shared:iosSimulatorArm64Test
```

- `-Pmatrix=floor` uses Kotlin 2.1.21 and Ktor 3.0.3, `-Pmatrix=latest` Kotlin 2.4.10 and Ktor 3.5.2. `-Pkotlin=<version>` and `-Pktor=<version>` override the Kotlin and Ktor versions of either matrix. `-PuseCentral` resolves the library from Maven Central instead of `mavenLocal()`.
- `legacy` consumes the library from a `com.android.library` module with `androidTarget()` and two flavor dimensions, the setup of older multiplatform apps.
- `-Pe2e=true` compiles the library's end-to-end suite against the public API alone and runs it against the reference server; `-Pstress=true` does the same for the stress suite.
- Device apps: build the Android app with `-PcableUrl=ws://<mac-lan-ip>:3000/cable`. For iOS, run `xcodegen generate` in `consumer-check/iosApp`, set `CABLE_URL` in `project.yml`, and run the `CableHarness` scheme. The launch argument `-pinForeground YES` turns the pin toggle on, so `xcrun simctl launch` can script the watchdog check.

Releasing: bump the version in `actioncable/build.gradle.kts` and `consumer-check/settings.gradle.kts`, then push a `v<version>` tag. The release workflow runs CI, including both end-to-end matrices, and then `publishAndReleaseToMavenCentral`. It needs the secrets `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_KEY_ID` and `SIGNING_KEY_PASSWORD`. Once the release is on Maven Central, run the workflow manually to check the published artifact with `consumer-check`.

## License

Apache License 2.0.
