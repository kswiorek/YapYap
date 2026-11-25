## Logging

How the core logs. This file derives from the code's own patterns (logging/AppLog.kt, logging/LoggingTypes.kt,
jvmMain/logging/JvmAppLogger.kt).

### AppLog facade with typed LogComponent/LogEvent

All core logging goes through the internal `AppLog` object facade with named parameters (`component = LogComponent.X`,
`event = LogEvent.Y`, `message`, optional fields map / throwable). Events and components are closed enums (LogEvent has
130 entries, LogComponent 13), never free-form strings. `AppLog.init(delegate)` installs a platform `AppLogger`
(JvmAppLogger writes a timestamped file log); tests use Noop/Printing doubles. No println and no logging framework
anywhere in common code — the only println in the codebase is inside JvmAppLogger's own sink. The GUI does not log; it
surfaces failures as toasts/state.

Sources: code · confidence 85.
Evidence:

- 62 of 224 sampled core files call AppLog (297 call sites total); 0 files use println or a logging framework in
  commonMain.
- logging/LoggingTypes.kt defines internal enums LogEvent (130 entries), LogComponent (13), LogLevel.
- Examples: logging/AppLog.kt, logging/LoggingTypes.kt, jvmMain/logging/JvmAppLogger.kt, wiring/NodeGraph.kt.

```kotlin
AppLog.info(component = LogComponent.ORCHESTRATOR, event = LogEvent.STARTED, message = "Router started")
```
