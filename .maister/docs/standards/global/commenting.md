## Commenting

### Let Code Speak

Write code that explains itself through structure and naming.

### Comment Sparingly

Add brief comments only when the logic isn't self-evident from the code.

### No Change Comments

Avoid comments about recent fixes or changes; comments should be timeless explanations, not changelogs.

### KDoc on types and contracts explaining semantics and failure modes

Types, interfaces and non-obvious members carry KDoc that explains the model semantics, ownership rules and failure
modes (what a value means, who writes it, what throws, what "absence" means), not restatements of the signature.
Comments are sparing inside function bodies and reserved for non-obvious decisions (why-comments at wiring sites,
ordering constraints). Section banner comments split multi-type vocabulary files.

Sources: code · confidence 82.
Evidence:

- 194 of 224 sampled core files (87%) contain KDoc.
- Sampled contracts document failure modes: `DagEngine.append` `@throws DagException.FrontierUnavailable`;
  `Orchestrator.resetApp` documents callable states; Router.kt documents why the pull path lives on MessageCarrier.
- Examples: orchestrator/dag/DagEngine.kt, routing/router/RoutingTypes.kt (each data class carries semantics KDoc),
  orchestrator/DefaultOrchestrator.kt, protocol/ProtocolTypes.kt.

```kotlin
/** Sole writer of message rows (docs/room events.md §3: the engine is the sole verdict writer — and exposes no reads at all). … */
internal interface DagEngine
```
