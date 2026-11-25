## Structure

How core types and files are organized. This file derives from the code's own structure (org.yapyap packages, 224
sampled core files) — there is no prose design doc for it; the patterns below are the only scheme that appears in the
codebase.

### Interface + Default* implementation pairs

Components are declared as an interface `X` in `X.kt` with the canonical implementation as `internal class DefaultX : X`
in `DefaultX.kt`, same package. Interfaces are the dependency type used by collaborators and wiring; `Default*` classes
are never referenced outside wiring/tests except by the composition root.

Sources: code · confidence 85.
Evidence:

- 71 of 224 sampled core files declare a top-level interface; 46 files declare a `Default*` class.
- 8 of 8 spot-checked pairs exist as `X.kt` + `DefaultX.kt` in the same package: DagEngine/DefaultDagEngine,
  IdentityStore/DefaultIdentityStore, MessagingService/DefaultMessagingService, SyncCoordinator/DefaultSyncCoordinator,
  CryptoSessionManager/DefaultCryptoSessionManager, PacketOutbox/DefaultPacketOutbox, TorTransport/DefaultTorTransport,
  InboundMessagePipeline/DefaultInboundMessagePipeline.

```kotlin
// DagEngine.kt
internal interface DagEngine {
    suspend fun append(roomId: RoomId, draft: MessageDraft): DagPayload
}

// DefaultDagEngine.kt
internal class DefaultDagEngine(private val messageRepository: MessageRepository /* , … */) : DagEngine
```

### PascalCase file-per-type with <Area>Types.kt vocabulary files

A file that contains one primary type is PascalCase-named after that type. Files holding a cluster of related small
types use a descriptive plural/concept name ending in `Types` (`ProtocolTypes.kt`, `RoutingTypes.kt`,
`CryptoSessionTypes.kt`, `WebRtcSessionTypes.kt`) or naming the concept (`NackPolicy.kt`, `Keys.kt`,
`EnvelopeProtections.kt`). No other naming scheme appears.

Sources: code · confidence 82.
Evidence:

- 190 of 224 sampled core files (85%) are named exactly after a type declared in them.
- 33 of the remaining 34 are coherent vocabulary/cluster files (`*Types.kt`, `*Helpers.kt`, `*Fold.kt`, policy/store
  grouping) — 223 of 224 (99.6%) follow the two-mode scheme.
- Examples: routing/router/DefaultRouter.kt, protocol/envelopes/MessageHeader.kt, orchestrator/dag/DagTypes.kt,
  routing/policy/NackPolicy.kt; `MessageHeader.kt` declares `internal class MessageHeader`; `ProtocolTypes.kt` groups
  PeerId, TorEndpoint and wire enums with section banner comments.
