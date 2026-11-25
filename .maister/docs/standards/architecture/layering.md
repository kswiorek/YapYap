## Layering

The core is layered like a small network stack: orchestrator → router → transports → protocol, each owning a vocabulary.
This file derives from docs/guide.md §2 (the layer descriptions — the authoritative rules-of-the-code document) and its
§9 checklist, with mechanism detail from docs/router.md and docs/transport.md. A generic three-layer
presentation/business/data split is explicitly rejected: the boundary that matters is who knows about devices and
packets, and who doesn't.

### Layer vocabulary separation

Code belongs to a layer by what vocabulary it speaks. Orchestrator code speaks accounts, rooms and payloads; router code
speaks devices, envelopes, retries and links; transport code only moves bytes. No layer leaks another's vocabulary: the
orchestrator never sees `BinaryEnvelope`, packet ids, link/session state, transport or Tor types; the router never
interprets orchestrator values; transports know nothing about the database, accounts, identity, crypto sessions or retry
policy. Pick the layer by vocabulary first when writing new code.

Sources: docs (guide.md §2, §9) · confidence 92.
Evidence:

- guide.md §2: the orchestrator "speaks accounts, rooms and payloads" and "never sees `BinaryEnvelope`, packet ids, link
  or session state, transport types, or Tor types."
- guide.md §9 step 1: "Pick the layer by vocabulary" — rooms/members/messages/accounts → orchestrator;
  devices/envelopes/retries/links → router; bytes only → transport.
- guide.md §2: a three-layer split would merge router and transports into "data" and lose the boundary that matters
  most.

### Orchestrator never addresses devices

The orchestrator models devices only as members of an account (identity facts: keys, type, status, provisional, declared
endpoint). It may name a device as the *subject* of an identity fact using `PeerId`, but never addresses one. The single
exception is bootstrap: before an account exists, an intro or recovery request is sent to a device id plus an
out-of-band `TorEndpoint`.

Sources: docs (guide.md §2) · confidence 91.
Evidence:

- guide.md §2: "Models devices only as **members of an account** … may name a device as the *subject* of an identity
  fact … but **never addresses** one."
- guide.md §2: the single bootstrap exception — an intro or recovery request goes to a device id plus an out-of-band
  `TorEndpoint`.

### Router treats payloads as opaque beyond the routing header

The router carries orchestrator values (a typing indicator's room, ping frontiers) but never interprets them: it never
branches on a room, never looks at the DAG or membership, never decides what content to send. When it needs content it
does not own (the frontiers it advertises in a ping), it asks through an interface it declares itself, implemented by
the orchestrator.

Sources: docs (guide.md §2) · confidence 91.
Evidence:

- guide.md §2: "carries them, never interprets them. It never branches on a room, never looks at the DAG or membership,
  and never decides what content to send."
- guide.md §2: content the router does not own is requested through an interface the router declares and the
  orchestrator implements.

### Transport types stop at the router

The router folds transport health into its own `LocalLinkStatus` (`Presence.localLinkStatus`); Tor/WebRTC transport
types never reach the orchestrator, which sees only the mapped public `LocalTransportStatus`. Tor's stall reason travels
as problem detail, never as headline.

Sources: docs (guide.md §2; transport.md) · confidence 90.
Evidence:

- guide.md §2: "Folds transport health into its own `LocalLinkStatus` (`Presence.localLinkStatus`); transport types stop
  at the router."
- transport.md (Status path to the GUI): "`TorHealth` … go from the transport to the router, which folds them into one
  `Router.localLinkStatus` … Tor's stall reason travels as problem detail, never as headline."

### Router is a lifecycle plus one service per concern

`Router` is only a lifecycle (`start`/`stop`/`isRunning`) plus one service per concern, each a narrow interface in
`routing/router`. An orchestrator component takes only the services it uses, never the whole router.

Sources: docs (guide.md §2; router.md) · confidence 90.
Evidence:

- guide.md §2: "An orchestrator component takes the services it uses, never the whole router."
- router.md service table: `messages` (`MessageCarrier`: sendMessage, incomingMessages, deliveries, sendTypingIndicator,
  typingIndicators, frontiers, startSync, reopenSync), `bootstrap` (`BootstrapCarrier`: sendBootstrap, bootstrapPackets,
  deliveries), `presence` (`Presence`: onlineAccounts, accountReach, localLinkStatus, localTorEndpoint, announceOnline),
  `files` (`FileCarrier`: pushFile, cancelFile, sendState, startFileRequest, abortFileFetch, endIncoming, fileEvents).

### Fan-out split: orchestrator per room, router per account

The orchestrator fans out per room; the router fans out per account (every device of the target account). Whoever
authors a room or global event reads the room's members after the fold commits, so a just-added member is included and a
removed one is not.

Sources: docs (guide.md §2) · confidence 90.
Evidence:

- guide.md §2: "Fans out per room; the router fans out per account. Whoever authors a room or global event reads the
  room's members after the fold commits, so a just-added member is included and a removed one is not."

### Protocol is the bottom of the graph; codecs only encode and decode

The protocol package depends on nothing else in the core. Codecs encode and decode; signing, hashing and encryption
happen elsewhere (room ids are hashed by the DAG's `RoomIds` from bytes protocol defines). The wire format is
hand-written: wire enums carry `wireValue: Byte` constants with a `fromWireValue` companion factory that errors on
unknown bytes; payloads and envelopes encode/decode through `ByteReader`/`ByteWriter` with `require()` bounds checks and
bounded string reads. `kotlinx.serialization` is for config persistence only (TOML/JSON config slices, GUI prefs) —
never on the wire.

Sources: docs (guide.md §2, §9), code · confidence 90.
Evidence:

- guide.md §2: "It is the bottom of the graph: it depends on nothing else in the core. Codecs encode and decode;
  signing, hashing and encryption happen elsewhere."
- guide.md §9 step 5: "Wire types go in `protocol`. A new envelope or payload gets a codec with round-trip tests and an
  observability profile if it adds routing metadata."
- code: all 15+ wire enums (`PacketType`, `DeviceType`, `RoomType`, `MessagePayloadFamily`, `DagPayloadType`, …) carry
  `wireValue: Byte` + `fromWireValue`; kotlinx.serialization is imported only by the config files (RuntimeConfig,
  RouterConfig, TorBackendConfig, WebRtcBackendConfig, CryptoSessionConfig, OrchestratorConfig, ConfigStore + GUI
  prefs), never on the wire.

```kotlin
internal enum class PacketType(val wireValue: Byte) {
    MESSAGE(1), SIGNAL(2), /* … */;
    companion object {
        fun fromWireValue(value: Byte) =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported packet type value: $value")
    }
}
```
