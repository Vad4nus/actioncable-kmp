# Contributing

CI runs every check below on each pull request.

## Tests

```bash
./gradlew :actioncable:testAndroidHostTest
```

```bash
./gradlew :actioncable:iosSimulatorArm64Test
```

On Intel Macs run `iosX64Test` instead. `-PiosSimulatorDevice="iPhone 16"` picks the simulator.

## Public API

```bash
./gradlew apiCheck
```

After an intended public API change, run `./gradlew apiDump` and commit the updated `actioncable/api/actioncable.klib.api`.

## End-to-end tests

Start the reference Rails server in `e2e-server/` (Ruby 3.4):

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

A Gradle run includes these suites only with the flag, so add `-Pe2e=true` to an IDE run configuration too. `-Pstress=true` adds the long-running stress suite; its network fault tests (`*ChaosStressTest`) need `cd e2e-server && python3 chaos.py` in place of the plain server.
