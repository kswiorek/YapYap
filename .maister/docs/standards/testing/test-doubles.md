## Test Doubles

How test doubles are written for the core. This file derives from the test code's own patterns (core commonTest/jvmTest
fixtures).

### Hand-written Fake*/Noop*/Recording* doubles; no mock framework

Test doubles are hand-written classes implementing the interface under test, named by behavior: `Fake*` (stateful
in-memory stand-ins), `Noop*` (accept-and-ignore), `Recording*` (capture calls for assertion), `Tracking*`, `Printing*`,
Map/Memory-backed stores. No mockito/mockk anywhere. Shared doubles live in the `org.yapyap.testfixtures` package
(`FakeClock`, `NoopRouter`, `Repositories`, `KeyStoreTestDoubles`); area-specific fixtures sit next to the collaborators
they fake (`RecordingTransportFakes` in transport.tor and transport.webrtc, `RouterTestFixtures`, `SyncTestFixtures`).

Sources: code · confidence 88.
Evidence:

- 0 of 171 core test files import io.mockk or org.mockito.
- 30+ distinct double class names counted across commonTest/jvmTest (FakeRoomRepository ×3, FakeMessageRepository ×3,
  RecordingRouter ×2, NoopRouter, NoopPresence, NoopMessageCarrier, TrackingPacketOutbox, RecordingTorTransport,
  PrintingAppLogger, MapBackedCryptoSessionStore, MemoryKeyStores contract, …).
- 8 shared fixtures in `org.yapyap.testfixtures`; per-area fixture files colocated with their tests.

```kotlin
/** A deterministic [Clock] for tests: time advances only when the test says so. */
class FakeClock(start: Instant = /* … */) : Clock {
    fun advanceBy(duration: Duration)
}

/** In-memory [PeerAvailabilityStore] for tests: no dispatcher, records writes into maps. */
internal class FakePeerAvailabilityStore : PeerAvailabilityStore
```
