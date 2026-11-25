## Resources

Compose Multiplatform string resources. This file merges the Gradle lint task (composeApp/build.gradle.kts) with the
.editorconfig rule that keeps formatters from undoing it.

### Single-line Compose string resource values

`<string>`/`<item>` values in `composeApp/src/commonMain/composeResources/values/strings.xml` must stay on a single line
with no leading/trailing whitespace: Compose Multiplatform (unlike Android res) renders embedded newlines as blank lines
in the UI. Enforced by the custom `checkComposeStringValues` Gradle task wired into `check`, which fails the build
naming the offending keys; `.editorconfig` disables hard-wrapping for XML (`max_line_length = off`) so formatters cannot
reintroduce the problem.

Sources: config, CI config · confidence 85.
Evidence:

- composeApp/build.gradle.kts: task `checkComposeStringValues` throws a `GradleException` listing keys whose `<string>`/
  `<item>` value contains a newline or is untrimmed; `tasks.named("check") { dependsOn(checkComposeStringValues) }`.
- .editorconfig: `[*.xml] max_line_length = off` — "values/strings.xml must keep every value on a single line — enforced
  by :composeApp:checkComposeStringValues".

```xml
<string name="app_title">YapYap</string>  <!-- value on one line, no wrapping or padding newlines -->
```
