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

Behaviour — classes and interfaces that do work — keeps file-per-type: the file is PascalCase-named after that type,
and a file holding two behaviour types splits. Data-only types — sealed vocabularies, enums, row/data classes, result
types — go into one `[Concern]Types.kt` per concern (`ProtocolTypes.kt`, `RoutingTypes.kt`,
routing/file/FileTypes.kt, routing/file/port/FilePortTypes.kt, routing/store/FileStoreTypes.kt), not one file each and
not riding along in a behaviour file. A data class with exactly one consumer may live in that consumer's file, but
first suspect it as an unneeded wrapper. Wire-concept files that pair a codec with the vocabulary it owns
(`FileHeader.kt`, `LargeFileFrame.kt`, `FileIds.kt`) and other concept-named files (`NackPolicy.kt`, `Keys.kt`) stay.

Sources: code; docs (docs/file/cleanup.md §2.13) · confidence 86.
Evidence:

- 190 of 224 sampled core files (85%) are named exactly after a type declared in them.
- 33 of the remaining 34 are coherent vocabulary/cluster files (`*Types.kt`, `*Helpers.kt`, `*Fold.kt`, policy/store
  grouping) — 223 of 224 (99.6%) follow the two-mode scheme.
- Examples: routing/router/DefaultRouter.kt, protocol/envelopes/MessageHeader.kt, orchestrator/dag/DagTypes.kt,
  routing/policy/NackPolicy.kt; `MessageHeader.kt` declares `internal class MessageHeader`; `ProtocolTypes.kt` groups
  PeerId, TorEndpoint and wire enums with section banner comments.
- cleanup.md §2.13: "Behavior — classes and interfaces that do work — keeps file-per-type"; "Data-only types … go in one
  `[Concern]Types.kt` per concern"; "A data class used by exactly one behavior type may live in that type's file — but
  first suspect it as an unneeded wrapper"; "Wire/protocol concept files stay".
- cleanup.md §2.13 moves: routing/file/port/FilePortTypes.kt, routing/file/FileTypes.kt, routing/store/FileStoreTypes.kt
  (`FileRequestProcessor.kt` split into `FilePullProcessor.kt` + `FileRequestStrategy.kt`).
