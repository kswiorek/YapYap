## Config and Limits

Where config types live and how size limits are chained. This file derives from docs/guide.md §2 (config service and
Size limits — the authoritative rules-of-the-code document), with the content-lifetime invariants from
docs/envelope-redesign.md §2/§11 and docs/e2ee.md.

### Each layer defines its own config type next to its code

Each layer defines its own config type next to its code (`OrchestratorConfig`, `RouterConfig`, `TorBackendConfig`, …).
The `config` package itself holds only layer-free types: the GUI's setting model (`Setting`, `ConfigValue`,
`UpdateResult`) and the config-file watcher. The aggregate of all layer configs (`RuntimeConfig`), the settings registry
that maps file keys onto it, and loading, watching and distributing config live in the composition root.

Sources: docs (guide.md §2) · confidence 90.
Evidence:

- guide.md §2 (config service): "each layer defines its own config type next to its code … `config` itself holds only
  layer-free types … The aggregate of all layer configs (`RuntimeConfig`), the settings registry that maps file keys
  onto it, and loading, watching and distributing config live in the composition root."

### Size limits are chained by the composition root

Each layer states only its own framing overhead (`BinaryEnvelope.maxContentBytes`,
`CryptoLimits.forSessionWireFrameBudget`). The composition root chains the limits from the transport limits
(`wiring/MessageLimits`, `wiring/FileLimits`) and hands each consumer its piece: the router `TransportLimits` and
`FileLimits`, crypto `CryptoLimits`, the orchestrator the max text size.

Sources: docs (guide.md §2) · confidence 89.
Evidence:

- guide.md §2 (Size limits): "each layer states only its own framing overhead … the composition root chains them from
  the transport limits (`wiring/MessageLimits`, `wiring/FileLimits`) and hands each consumer its piece: the router
  `TransportLimits` and `FileLimits`, crypto `CryptoLimits`, the orchestrator the max text size."

### Content-lifetime config invariants are checked at wiring

Config invariants relating content lifetimes to crypto session retention are enforced by the composition root
(`wiring/ConfigInvariants`): `binaryEnvelopeLifetime < supersededSessionRetention` (relayed messages keep their first
hop's expiry) and, for files, `fileTransferTtl < supersededSessionRetention` — so sealed/ratcheted content stays
openable for as long as any copy can arrive.

Sources: docs (envelope-redesign.md §2, §11; e2ee.md) · confidence 88.
Evidence:

- envelope-redesign.md §11 (Implementation notes): "`wiring/ConfigInvariants` also checks
  `binaryEnvelopeLifetime < supersededSessionRetention` (relayed messages keep their first hop's expiry)."
- envelope-redesign.md §2: "The config invariant becomes: content lifetime (`binaryEnvelopeLifetime`,
  `fileTransferTtl`) < `supersededSessionRetention`."
- e2ee.md (Planned: session-retention window for file transfers): "the config invariant
  `fileTransferTtl < supersededSessionRetention` is enforced at wiring."
