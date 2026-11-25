## State

How GUI state is organized in the Compose app. This file derives from the code's own patterns (gui/state, gui/session),
corroborated by docs/gui-api.md ("Flows are hot — collect them from a coroutine scope") and the ConfigStore
StateFlow-distribution pattern in the core.

### Controller pattern: constructor scope + services, StateFlow exposure, runCatching error surfacing

GUI state lives in screen-scoped controller classes that take (CoroutineScope, OrchestratorRuntime services) via
constructor, keep private `MutableStateFlow` backing fields, expose `StateFlow`, and launch collection/send jobs on the
injected scope. The process-level `YapSession` owns the app CoroutineScope and the Orchestrator, created in `main()`/
`Application` — never in composition. Calls into core are wrapped in `runCatching` and failures surface as toast
messages or state error fields — never silent, never thrown into composition.

Sources: code · confidence 82.
Evidence:

- 4 of 4 GUI controllers follow the pattern: ChatController, RoomsController, SetupController, UiPreferencesController
  (constructor scope + services, private MutableStateFlow, public StateFlow).
- 12 of 47 GUI files wrap core interaction in `runCatching`; YapSession.resetAndReboot/start surface failures via
  `showToast`, ChatController maps exceptions into `ChatUiState.openError`/`sendError`.
- YapSession.kt: "Process-level session: owns the Orchestrator, the app CoroutineScope … created in main ()
  /Application/MainViewController, never in composition."

```kotlin
class ChatController(
    private val scope: CoroutineScope,
    private val runtime: OrchestratorRuntime,
    private val roomId: RoomId, /* … */
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
}

runCatching { orch.start() }
    .onFailure { showToast("Startup failed: ${it.message}") }  // YapSession
```
