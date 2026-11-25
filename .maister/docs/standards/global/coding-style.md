## Coding Style

### Naming Consistency

Follow established naming patterns for variables, functions, classes, and files throughout the project.

### Automatic Formatting

Use automated tools to enforce consistent indentation, spacing, and line breaks.

### Descriptive Names

Choose names that clearly communicate intent; avoid cryptic abbreviations or single-letter identifiers outside tight
loops.

### Focused Functions

Write functions that do one thing well; smaller functions are easier to read, test, and maintain.

### Uniform Indentation

Standardize on spaces or tabs and enforce with editor/linter settings.

### No Dead Code

Remove unused imports, commented-out blocks, and orphaned functions instead of leaving them behind.

### No Backward Compatibility Unless Required

Avoid extra code paths for backward compatibility unless explicitly needed.

### DRY (Don't Repeat Yourself)

Extract repeated logic into reusable functions or modules.

### No star imports in Kotlin

Kotlin files must use fully explicit imports; IntelliJ 'Optimize Imports' must never collapse imports into `.*` star
imports. Star-importing the generated compose-resources accessors hides which resource keys each file actually uses and
churns wildly when keys are added. Enforced by .editorconfig (star-import thresholds maxed out = effectively 'never
star-import').

Sources: config · confidence 85.
Evidence:

- .editorconfig: `ij_kotlin_name_count_to_use_star_import = 2147483647` for `[*.{kt,kts}]` (threshold maxed out =
  effectively 'never star-import'); same for `_for_members`.
- .editorconfig: comment — star imports of `yapyap.composeapp.generated.resources.*` are forbidden because explicit
  imports stay put when keys are added.

```kotlin
import yapyap.composeapp.generated.resources.Res.string.app_title  // explicit per-key import
// never: import yapyap.composeapp.generated.resources.Res.*
```
