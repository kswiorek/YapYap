## Reactive State

How observable state is exposed. This file derives from the code's own patterns (62 of 224 sampled core files expose
StateFlow/Flow), corroborated by docs/gui-api.md ("Flows are hot") and the ConfigStore distribution pattern.

### StateFlow/Flow as the observable surface

Components expose observable state as kotlinx.coroutines `StateFlow` (or shared `Flow`) properties on their interface;
hot streams use the private backing-field idiom: `private val _x = MutableStateFlow(...)` exposed as
`val x: StateFlow<T> = _x.asStateFlow()` (or `MutableSharedFlow` + `asSharedFlow()` for events). The same idiom is used
by the GUI controllers. StateFlow is also the config-distribution mechanism (ConfigStore exposes one StateFlow per
config slice).

Sources: code, docs (gui-api.md) · confidence 80.
Evidence:

- 62 of 224 sampled core files expose StateFlow/Flow to collaborators; 23 use the backing `_field` + `asStateFlow()`/
  `asSharedFlow()` idiom; GUI: 6 files (all controllers/session) use MutableStateFlow backing + StateFlow exposure.
- gui-api.md: "Flows are hot — collect them from a coroutine scope" (see frontend/gui-boundary.md).
- Examples: orchestrator/Orchestrator.kt (state, onboardingState, bootProgress, transportStatus as StateFlow),
  orchestrator/dag/DefaultDagEngine.kt (`_verificationStateChanges` + `asSharedFlow`), wiring/ConfigStore.kt,
  gui/state/ChatController.kt.

```kotlin
private val _verificationStateChanges = MutableSharedFlow<VerificationStateChange>(extraBufferCapacity = 64)
override val verificationStateChanges: Flow<VerificationStateChange> = _verificationStateChanges.asSharedFlow()

private val _state = MutableStateFlow(ChatUiState())
val state: StateFlow<ChatUiState> = _state.asStateFlow()  // GUI ChatController
```
