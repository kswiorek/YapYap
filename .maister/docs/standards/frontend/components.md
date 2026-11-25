## Components

### Single Responsibility

Each component should do one thing well.

### Reusability

Design components to work across different contexts with configurable props.

### Composability

Build complex UIs by combining smaller components rather than creating monoliths.

### Clear Interface

Define explicit, documented props with sensible defaults.

### Encapsulation

Keep implementation details private; expose only what's necessary.

### Consistent Naming

Use descriptive names that indicate purpose and follow team conventions.

### Local State

Keep state as close to where it's used as possible; lift only when needed.

### Minimal Props

If a component needs many props, consider composition or splitting it.

### Documentation

Document usage, props, and examples to help team adoption.

### Yap-prefixed design system with variant enums

Reusable UI widgets and theme tokens carry the brand prefix: components `YapButton`, `YapSwitch`, `YapTextField`,
`YapLogo`; theme `YapTheme`, `YapThemeWrapper`, `YapMotion`, `YapRadius`, accessors `yapColors`/`yapType`. Widgets are
top-level `@Composable` functions taking a `Modifier` and default-styled parameters; variants are small enums
(`YapButtonVariant` Primary/Secondary/Danger/Ghost) rather than boolean flags. Screens and dialogs are grouped under
`gui/screens/<area>/`.

Sources: code · confidence 80.
Evidence:

- 7 of 8 component files and 3 of 3 theme files use the Yap/yap prefix; 49 top-level composable functions across 47 GUI
  files.
- Components take `(text, onClick, modifier = Modifier, variant = YapButtonVariant.Primary, enabled = true, …)` with
  KDoc on non-obvious parameters.
- Examples: gui/components/YapButton.kt, gui/theme/YapTheme.kt, gui/screens/shell/MainShell.kt (imports yapColors,
  yapType, YapRadius).

```kotlin
@Composable
fun YapButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: YapButtonVariant = YapButtonVariant.Primary,
    enabled: Boolean = true,
)
```
