## Dependencies

Which package may import which. This file derives from docs/guide.md §3 (the layer dependency table and
ArchitectureTest), the authoritative rules-of-the-code document; the table is mechanically enforced by
`core/src/jvmTest/kotlin/org/yapyap/ArchitectureTest.kt`.

### Layer dependency table (import rules)

A package may import only what its row allows (plus `logging`, `config`, `persistence` and the standard libraries):
protocol → nothing; transport → protocol; crypto → protocol; protection → crypto, protocol; routing → transport,
protection, protocol, crypto's identity views (`crypto.identity`); orchestrator → routing (`Router`, its vocabulary, and
the ports it declares), crypto, protocol; config → protocol; persistence → protocol; wiring → everything. Cross-layer
use of another layer's config is denied — each layer reads only its own config (orchestrator may not import
`routing.router.RouterConfig` or `crypto.e2ee.CryptoSessionConfig`; routing may not import
`crypto.e2ee.CryptoSessionConfig`).

Sources: docs (guide.md §3), code, CI config · confidence 92.
Evidence:

- guide.md §3: the dependency table quoted in full, plus "A package may import only what its row allows (plus `logging`,
  `config`, `persistence` and the standard libraries)."
- guide.md §9 step 2: "Check the dependency table. If you need something from a higher layer, declare an interface in
  your own package and let the higher layer implement it."
- code: all 224 sampled core files live under `org.yapyap.<layer>[.<subarea>]`; ArchitectureTest.kt `allowed`/`denied`
  maps encode the table, including the config-isolation denials.

### config and persistence never import a layer back

`config` and `persistence` are importable from every layer, so they never import a layer back (only `protocol` and
`logging`); otherwise they would be a back door around the dependency table.

Sources: docs (guide.md §3) · confidence 91.
Evidence:

- guide.md §3: "`config` and `persistence` are importable from every layer, so they never import a layer back (only
  `protocol` and `logging`); otherwise they would be a back door around the table."

### Lower layers declare their own ports; never import upper-layer types

When a lower layer needs something from a higher one, it declares an interface in its own package and the higher layer
implements it; the composition root connects them. Lower layers never import upper-layer types. The router's
`SyncPayloadProvider` / `FrontierSnapshotProvider` are declared by routing and implemented by the orchestrator
(`orchestrator/sync`, working per requesting account).

Sources: docs (guide.md §3, §6) · confidence 92.
Evidence:

- guide.md §3: "When a lower layer needs something from a higher one, it **declares an interface in its own package**
  and the higher layer implements it. The composition root connects them. Lower layers never import upper-layer types."
- room events.md §6 (As built, sync split): the serve gate (`DefaultSyncPayloadProvider`) and the frontier provider
  moved to `orchestrator/sync`; "the router declares them as `SyncPayloadProvider` / `FrontierSnapshotProvider`."

### Exceptions cross a layer boundary only as the receiving layer's vocabulary

Errors are translated at layer boundaries into the vocabulary of the layer that observes them; a lower layer's internal
failure never surfaces raw to a higher layer (a crypto failure becomes a protection verdict before the router sees it).
Each layer owns a sealed exception vocabulary (`TransportException`, `CryptoException`, `CryptoSessionException`,
`ProtectionException`, `DagException`) with typed reason enums; the boundary maps lower-layer failures upward through
explicit companion mapping functions, and the receiving layer's exception carries the verdict (PERMANENT/DEFER) so the
caller never needs to understand the cause.

Sources: docs (guide.md §3), code · confidence 90.
Evidence:

- guide.md §3: "Exceptions cross a layer boundary only as the receiving layer's own vocabulary (e.g. a crypto failure
  becomes a protection verdict before the router sees it)."
- code: `ProtectionException` (sealed) carries `(disposition, reason)` and provides `map`/`mapCryptoSessionException`/
  `mapEncryptFailure` to translate crypto failures at the boundary; encrypt-side unknowns default to DEFER, decrypt-side
  to PERMANENT (the KDoc documents the asymmetry).
- code: interfaces document `@throws` where a method legitimately throws (e.g. `DagEngine.append` →
  `@throws DagException.FrontierUnavailable`).

```kotlin
companion object {
    fun mapCryptoSessionException(error: CryptoSessionException): ProtectionException =
        when (error) {
            is CryptoSessionException.Replay -> Duplicate(error) // exhaustive; carries the verdict
            // …
        }
}
```

### Dependency rules enforced by ArchitectureTest; debt only shrinks

The dependency (and ownership) rules are checked by `ArchitectureTest` in jvmTest; a new dependency that breaks the
table fails the build. Known violations are listed there by file as acknowledged debt, and that list is only allowed to
shrink — a debt entry that no longer occurs also fails the test (delete it). New cross-layer imports or public API
require editing the corresponding test's allowlist — a deliberate, reviewable act.

Sources: docs (guide.md §3), code, CI config · confidence 91.
Evidence:

- guide.md §3: "The rules are checked by `ArchitectureTest` (jvmTest); a new dependency that breaks the table fails the
  build. Violations still being worked off are listed there by file as known debt — the list only shrinks."
- ArchitectureTest.kt KDoc: "Known debt is listed in [knownDebt] … a new violation fails this test; so does a debt entry
  that no longer occurs (delete it)."
- ArchitectureTest.kt also enforces the generated-`*Queries` ownership table (`queryOwners`) — the gate runs on local
  `./gradlew check` (see build/release.md for the current CI gap).
- CI config: ArchitectureTest/PublicApiSurfaceTest/GuiApiBoundaryTest are read in full as the executable governance
  layer; whitelists are data in the tests so changes are reviewable diffs.

```kotlin
private val allowed: Map<String, List<String>> = mapOf(
    "protocol" to emptyList(),
    "transport" to listOf("protocol"),
    "routing" to listOf("transport", "protection", "protocol", "crypto.identity"),
    // … the whole dependency table lives here as reviewable data
)
```
