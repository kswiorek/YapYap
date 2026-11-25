## Vocabularies

How closed result and state vocabularies are modeled. This file derives from the code's own patterns (42 of 224 sampled
core files declare sealed types; 96 `data object` singleton variants).

### Sealed interfaces with data class/object variants for result and state vocabularies

Closed vocabularies (results, outcomes, side effects, UI states) are sealed interfaces or sealed classes whose cases are
small data classes or `data object` singletons. No strings/booleans as vocabulary. Exhaustive when-statements are
deliberate: "a new [enum] must decide here at compile time" — no else branch on closed types.

Sources: code · confidence 75.
Evidence:

- 42 of 224 sampled core files declare sealed types; 96 `data object` singleton variants counted across core; 5 GUI
  files declare sealed types.
- Result/state vocabularies found as sealed interfaces: `InboundHandleResult`, `OutboxOutcome`, `PeerSendOutcome`,
  `InboundSideEffect` (RoutingTypes.kt), GUI `PendingState` (ChatController.kt).
- routing/policy/NackPolicy.kt carries the exhaustive-on-purpose KDoc ("a new enum must decide here at compile time").

```kotlin
internal sealed interface PeerSendOutcome {
    data class Queued(val relaysDeposited: Int = 0) : PeerSendOutcome
    data object Deferred : PeerSendOutcome
    data object PermanentFailure : PeerSendOutcome
}
```
