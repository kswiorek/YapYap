## Composition Root

How the core is assembled. This file derives from docs/guide.md §5 (the composition root — the authoritative
rules-of-the-code document), with routing detail from docs/router.md. The built composition roots are
`wiring/DefaultNodeAssembler.kt`, `wiring/VaultGraph.kt` and `wiring/NodeGraph.kt`.

### Two-phase Vault/Node composition, built fresh every start

The composition root (`wiring`) builds the core in two phases, each built fresh on every start. **Vault**
(`VaultGraph`): what exists as soon as the data directory is open, before any local identity — config store, key store,
database, identity store, provisioning. **Node** (`NodeGraph`): everything that needs the local identity; its
constructor only wires, and `start()` starts the parts in dependency order.

Sources: docs (guide.md §5) · confidence 90.
Evidence:

- guide.md §5: "The composition root (`wiring`) builds the core in two phases, each built fresh on every start" — Vault
  ("before any local identity") and Node ("Its constructor only wires; `start()` starts the parts in dependency order").

### Orchestrator never constructs; wiring implements its lifecycle ports

The orchestrator never constructs anything. It declares what its lifecycle needs (`NodeAssembler`, `Vault`, `Node` in
orchestrator/boot), and wiring implements those. The public entry point `OrchestratorFactory` lives in wiring; each
platform (JVM, Android, iOS) gives it a `Platform` (keyring, database driver, Tor and WebRTC backends, logger, config
watcher). `DefaultOrchestrator` is the lifecycle only: lock, boot diagnosis, setup, start/stop, reset, and the public
state flows.

Sources: docs (guide.md §5) · confidence 90.
Evidence:

- guide.md §5: "The orchestrator never constructs anything. It declares what its lifecycle needs … and `wiring`
  implements those. The public entry point `OrchestratorFactory` lives in `wiring`."
- guide.md §5: "`DefaultOrchestrator` is the lifecycle only: lock, boot diagnosis, setup, start/stop, reset, and the
  public state flows."

### Components subscribe to their own inputs; wiring has no launch/collect

A component that reacts to another's flow takes that flow (or its owner) in its constructor and collects it in its own
`start()`. The composition root contains no `launch`/`collect`; the only collectors in the lifecycle mirror the node's
flows into the stable public ones.

Sources: docs (guide.md §5), code · confidence 91.
Evidence:

- guide.md §5: "**Components subscribe to their own inputs.** … The composition root contains no `launch`/`collect`; the
  only collectors in the lifecycle mirror the node's flows into the stable public ones."
- code: `NodeGraph.start()` calls `component.start(scope)` sequentially with BootProgress callbacks; 21 core components
  expose `start(scope: CoroutineScope)`; `.launch` appears in only 1 of the wiring files (ConfigStore, itself a
  file-watcher component).

### Constructor injection only — no service locators, no lateinit wiring

Each component takes its dependencies through its constructor as interfaces; no service locators, no `lateinit` wiring
after construction, no DI framework. Something that needs a value only known at runtime (the local device id) is built
after that value is resolved, not patched in later. Construction and assembly happen only in the wiring layer — the only
place `Default*` classes are instantiated (besides tests).

Sources: docs (guide.md §5, §9), code · confidence 91.
Evidence:

- guide.md §5: "Each component takes its dependencies through its constructor as interfaces; no service locators, no
  `lateinit` wiring after construction. Something that needs a value only known at runtime (the local device id) is
  built after that value is resolved, not patched in later."
- guide.md §9 step 4: "Wire it in the composition root, with constructor-injected interfaces."
- code: 372 `private val` constructor-injected fields across sampled core files; all 3 composition roots live in wiring;
  `NodeGraph` constructs ~40 components via named constructor arguments.

```kotlin
// wiring/NodeGraph.kt — construction happens only here
private val syncCoordinator = DefaultSyncCoordinator(
    pipeline = pipeline, /* … */
    roomChanges = roomEventProjector.stateChanges,
)

override suspend fun start(onProgress: (BootProgress) -> Unit) {
    maintenance.start(scope); router.start(); pipeline.start(scope) // start in dependency order; never launch
}
```

### Routing is its own composition root below wiring

`DefaultRouter` composes the kernel and services, but `RoutingContext` and `PeerLinks` are built in wiring, never in
`DefaultRouter` — the context is ambient facts and carries no stores, engines or policies; routing-owned state (routes,
the packet deduplicator, the outbox) and everything routing builds are explicit dependencies of their owners.
File-transfer sizes arrive as the composition root's `FileLimits` piece (chained from the transport limits), not router
config. `DefaultRouter` reads the Tor endpoint from `TorTransport.localTorEndpoint` and caches no copy.

Sources: docs (router.md) · confidence 87.
Evidence:

- router.md (DefaultRouter responsibilities): "build `PeerLinks` + `RoutingContext` in wiring, never here — the context
  is ambient facts" and "Routing is its own composition root below wiring."
- router.md (RoutingContext): "It carries **no** stores, engines or policies: routing-owned state … and everything
  routing builds are explicit dependencies of their owners."
- router.md: "file-transfer sizes arrive as the composition root's `FileLimits` piece (chained from the transport
  limits, guide §2); they are not router config."

### Platform abstraction via internal interfaces; minimal expect/actual

Common code depends on platform capabilities through internal interfaces — `Platform`, `DriverFactory`,
`KeyringSessionFactory`, `AppLogger`, `ConfigFileWatcher`, `FileAccess`, `NetworkMonitor` — implemented per source set
as `Jvm*`/`Java*` classes in jvmMain. expect/actual is used sparingly: only 3 expect declarations in the whole core
(`OrchestratorFactory`, the `DataDirLock` functions, the Tor no-exec backend), with actual implementations in files
suffixed `.jvm.kt`. New platform pieces should prefer an interface plus a per-platform implementation over new expect
declarations.

Sources: code · confidence 78.
Evidence:

- 3 expect declarations in commonMain (wiring/OrchestratorFactory.kt, orchestrator/boot/DataDirLock.kt, tor/backend)
  vs ~8 platform interfaces supplied via wiring/Platform.kt.
- 3 of 3 actual files use the `.jvm.kt` suffix: DataDirLock.jvm.kt, OrchestratorFactory.jvm.kt,
  KmpTorNoExecBackend.jvm.kt; other jvmMain implementations are `Jvm*`/`Java*`-prefixed (JvmAppLogger,
  JvmConfigFileWatcher, JvmNetworkMonitor, JavaKeyringSessionFactory, JvmWebRtcBackend, JvmEncryptedDriverFactory).
- Examples: wiring/Platform.kt, core/src/jvmMain/kotlin/org/yapyap/wiring/OrchestratorFactory.jvm.kt,
  core/src/jvmMain/kotlin/org/yapyap/logging/JvmAppLogger.kt.

```kotlin
// wiring/Platform.kt — a platform capability as an internal interface
internal interface Platform {
    val keyringSessionFactory: KeyringSessionFactory
    fun driverFactory(masterKey: ByteArray, databaseFile: Path): DriverFactory
    fun logger(logDirectory: Path): AppLogger
    // …
}
```
