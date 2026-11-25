## Test Style

How tests are written and named. This file derives from the test code's own patterns (147 core `*Test.kt` files).

### kotlin.test with method_state_expectation names, no backticks

All tests use kotlin.test (`@Test` + `assertTrue`/`assertEquals`/`assertFailsWith`/`fail`) — no JUnit assertions. Test
classes are named `<Subject>Test` and the file name always matches the class exactly. Test functions are plain camelCase
(no backticked sentences): verb_subject_expectation segmented by underscores, e.g.
`encode_decode_roundTrip_emptyPayload`, `start_throwsWhenAlreadyStarted`. Doubles/fixture classes are not suffixed
`Test` and live in the same package structure as the code under test.

Sources: code · confidence 85.
Evidence:

- 147 of 147 core `*Test.kt` files import kotlin.test; 0 file/class-name mismatches.
- 1224 test/helper functions use plain camelCase names vs 3 backticked ones (all in the jvmTest governance tests
  ArchitectureTest/PublicApiSurfaceTest/GuiApiBoundaryTest); 343 use method_state_expectation underscore segmentation.
- Examples: protocol/envelopes/MessageEnvelopeCodecTest.kt, routing/router/DefaultRouterContractTest.kt
  (`start_startsTransports_updatesTorEndpoint_stop_clearsRunning`), crypto/e2ee/X3dhHandshakeTest.kt.

```kotlin
@Test
fun sendMessage_withNoPeers_doesNotSendTor() = runBlocking {
    /* … */
    assertEquals(0, result.devicesQueued)
    assertTrue(tor.sends.isEmpty())
}
```

### Coroutine tests as expression-body runTest/runBlocking functions

Suspending behavior is tested by making the `@Test` function itself expression-body: `fun x() = runTest { … }`
(kotlinx-coroutines-test, virtual time) is the dominant form; `runBlocking { }` is the secondary form. Real-time waits
are avoided; deterministic doubles (`FakeClock`, an `epochSeconds` helper) control time.

Sources: code · confidence 82.
Evidence:

- 774 test functions use `= runTest {` and 170 use `= runBlocking {` across core commonTest/jvmTest; withTimeout/delay
  appear in integration-style tests only.
- Deterministic time via testfixtures `FakeClock` + `epochSeconds` helper used across codec/dag/router tests.
- Examples: routing/router/DefaultRouterContractTest.kt (`= runBlocking`), persistence/SingleConnectionJdbcDriverTest.kt
  (`= runTest`), orchestrator/dag/DefaultDagEngineTest.kt (FakeClock).

```kotlin
@Test
fun start_startsTransports_updatesTorEndpoint_stop_clearsRunning() = runBlocking {
    router.start()
    assertTrue(router.isRunning())
}
```
