## API Surface

How `:core`'s public surface is kept deliberate. This file merges the build-config rule (`core/build.gradle.kts`), the
enforcing test (`core/src/jvmTest/kotlin/org/yapyap/PublicApiSurfaceTest.kt`) and the CI analysis into one standard. The
two-module layering (:composeApp depends on :core, never the reverse; :core declares zero GUI framework dependencies) is
the structural counterpart — see frontend/gui-boundary.md for the consumer-side rule.

### Explicit API mode with a whitelisted public surface for :core

All declarations in `:core` must carry explicit visibility modifiers — `explicitApi()` is enabled for the whole module,
so the `public` keyword is required and internal helpers must be marked `internal` deliberately (generated SQLDelight
sources already comply). The default is internal: a top-level declaration may only be public if deliberately listed on
`PublicApiSurfaceTest`'s two-tier whitelist (GUI API + shared-internal vocabulary kept public only for
SQLDelight-generated code); a missing modifier or an unlisted public type fails the build. The `internal` modifier spans
all core source sets but is invisible to `:composeApp`, which is exactly the GUI boundary.

Sources: config, code, CI config · confidence 88.
Evidence:

- core/build.gradle.kts: `kotlin { explicitApi() }` with the comment "Strict explicit API mode: SQLDelight 2.x generates
  explicit modifiers, so generated sources comply. The GUI boundary itself is enforced by PublicApiSurfaceTest."
- PublicApiSurfaceTest.kt: "Every declaration in core's main sources carries an explicit visibility modifier … A missing
  modifier fails the build — no silent `public` defaults"; "Every `public` top-level declaration is on the whitelist
  below. Anything else must be `internal`. Adding a new `public` type fails until it is deliberately listed here."
- code: 205 of 224 sampled core files (92%) declare explicit internal/public top-level declarations; the whitelist has ~
  100 entries in two tiers (GUI API, shared-internal vocabulary).

```kotlin
public interface Orchestrator {                    // whitelisted GUI entry point
    public val state: StateFlow<OrchestratorState>
}
internal class DefaultDagEngine(/* … */) : DagEngine  // everything not deliberately public is internal
```
