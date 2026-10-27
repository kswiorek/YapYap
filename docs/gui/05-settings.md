# 05 — Settings

There are **two independent settings systems**. Keep them apart; do not pour one into the other.

|               | GUI settings                                        | Backend / network settings    |
|---------------|-----------------------------------------------------|-------------------------------|
| Owner         | `composeApp`                                        | `core` (`ConfigService`)      |
| Content       | appearance, notifications, chat behaviour, keybinds | router/sync tuning, retention |
| Storage       | local UI prefs file                                 | `state.toml` + overrides      |
| Audience      | every user                                          | operators / advanced          |
| Changes       | instant, no restart                                 | validated, may be rejected    |
| Section in UI | most of the settings modal                          | **Network → Advanced**        |

The GUI **must not** hard-code values that core already exposes (e.g. max message size comes from
`messaging.maxTextMessageBytes`, not a constant).

---

## 1. UI preference store

Define one immutable model and one writer:

```kotlin
@Serializable
data class UiPreferences(
    val appearance: AppearancePrefs = AppearancePrefs(),
    val notifications: NotificationPrefs = NotificationPrefs(),
    val chat: ChatPrefs = ChatPrefs(),
    val layout: LayoutPrefs = LayoutPrefs(),
    val schemaVersion: Int = 1,
)
```

- Persist as JSON in the platform data dir (`dataDirectory/ui-prefs.json`).
- Write debounced (250 ms) on change; read once at startup before first frame.
- Unknown keys survive a round-trip (use `ignoreUnknownKeys` + keep raw map) so downgrades don't
  wipe settings.
- `schemaVersion` gates migrations. v1 is the only version; add a migration function now so the
  shape exists.

Expose it as `StateFlow<UiPreferences>` from a `UiPreferencesController` in the composition root.
Components read individual fields; only the settings screens write.

---

## 2. Discord's catalogue → what we adopt

| Discord section                                                | State                                               | What we ship                                                                                               |
|----------------------------------------------------------------|-----------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| My Account                                                     | **ready**                                           | Read-only identity: name, account ID (copyable), role, recovery-key status, devices.                       |
| Profiles                                                       | **stub**                                            | Profile editor page (W30) with avatar/banner slots and disabled fields + `Soon`.                           |
| Privacy & Safety                                               | **stub**                                            | Blocked list + "who can message me" rows, all `Soon`.                                                      |
| Authorized Apps / Connections                                  | **out**                                             | No OAuth / third-party platform.                                                                           |
| Devices                                                        | **ready**                                           | Real: `identity` + `account`.                                                                              |
| Family Centre / Nitro / Boost / Subscriptions / Gift / Billing | **out**                                             | Commerce does not exist. Do not stub.                                                                      |
| Voice & Video                                                  | **stub**                                            | The whole pane with device pickers and sliders rendered but inert + `Soon`.                                |
| Notifications                                                  | **ready + stub**                                    | Local desktop notifications are real; push and sounds are stubs.                                           |
| Keybinds                                                       | **ready (read-only)** + **stub** for customisation. |
| Language & Time                                                | **partial**                                         | Time format is real; language row is a stub.                                                               |
| Appearance                                                     | **mostly ready**                                    | Theme (dark), accent, density, message display, text size, motion are real; light/custom themes are stubs. |
| Accessibility                                                  | **merged into Appearance**                          | Reduce motion, high contrast, saturation, link underline are real.                                         |
| Text & Images                                                  | **stub**                                            | Embed/preview toggles render with `Soon`.                                                                  |
| Advanced                                                       | **ready**                                           | Data dir, log level, reset.                                                                                |

---

## 3. The sections we ship

### 3.1 Account

| Row          | Control                             | Source                              |
|--------------|-------------------------------------|-------------------------------------|
| Display name | text (disabled)                     | `identity.localAccount.displayName` |
| Account ID   | mono text + copy button             | `AccountView.accountId`             |
| Network role | chip (`Owner` / `Admin` / `Member`) | `AccountView.role`                  |
| Recovery key | status + `Show instructions`        | local flag from setup               |
| This device  | type + last seen                    | `DeviceView`                        |

No editable fields — changing identity is a network event, not a setting.

### 3.2 Notifications

| Row                               | Control | Default                                             |
|-----------------------------------|---------|-----------------------------------------------------|
| Desktop notifications             | switch  | on                                                  |
| Notification sound                | switch  | on                                                  |
| Show unread badges                | switch  | on                                                  |
| Show mention badges               | switch  | on *(mentions aren't a backend feature — see note)* |
| Notify when the window is focused | switch  | off                                                 |
| Taskbar / dock badge              | switch  | on (desktop only)                                   |

> **Note:** YapYap has no mention system yet. Render the mention rows with the `Soon` chip rather
> than hiding them, so the notification model stays visible as it fills in.

### 3.3 Chat

| Row                  | Control                       | Default    |
|----------------------|-------------------------------|------------|
| Send with Enter      | switch (off = Ctrl/Cmd+Enter) | on         |
| Show character count | switch                        | on         |
| Message display      | segmented: Cozy / Compact     | Cozy       |
| Font size            | slider 14–20 → `body` scale   | 16         |
| UI zoom              | slider 80–150 %               | 100        |
| Timestamps           | dropdown: 12h / 24h           | 24h        |
| Reduce motion        | switch                        | follows OS |

### 3.4 Appearance

| Row                                              | Control                                                | Default       | Alpha?                                    |
|--------------------------------------------------|--------------------------------------------------------|---------------|-------------------------------------------|
| Theme                                            | segmented: Dark / Light / System                       | Dark          | dark only                                 |
| Accent colour                                    | 6 swatches (blurple, green, yellow, red, purple, teal) | blurple       | yes                                       |
| Density                                          | segmented: Default / Compact                           | Default       | yes                                       |
| Chat message display                             | segmented: Cozy / Compact                              | Cozy          | yes                                       |
| Space between message groups                     | slider 0–24                                            | 16            | yes                                       |
| Text size in chat                                | slider 12–20                                           | 16            | yes                                       |
| Always underline links                           | switch                                                 | off           | yes                                       |
| Show on/off indicators (status dots)             | switch                                                 | on            | yes                                       |
| Enable reduced motion                            | switch                                                 | follows OS    | yes                                       |
| Enable high contrast                             | switch                                                 | off           | yes                                       |
| Saturation                                       | slider 0–100 %                                         | 100           | yes                                       |
| Show member list by default                      | switch (≥ 1024 dp only)                                | on for groups | yes                                       |
| Sidebar width                                    | slider 240–360                                         | 300           | yes                                       |
| Show user avatars                                | switch                                                 | on            | **gated** — avatars are generated for now |
| Show emoji reactions                             | switch                                                 | on            | gated (no reactions yet)                  |
| Show embeds and link previews                    | switch                                                 | on            | gated                                     |
| Show spoiler content                             | switch                                                 | off           | gated                                     |
| Open threads in split view                       | switch                                                 | off           | gated                                     |
| Preview emoji / mentions / markdown while typing | switch                                                 | on            | gated (needs the parser)                  |
| Convert emoticons to emoji                       | switch                                                 | on            | gated                                     |

Rows marked **stub** render with the `Soon` chip (`03-ux-patterns.md` §8.1) and answer with the
"coming in a later build" toast. They are visible on purpose: the settings page should look stable
as features land, not grow a new row every sprint.

`Theme` must be honoured by the theme layer from day one even though Light is a stub — otherwise
you'll retrofit every colour.

### 3.5 Keybinds

Read-only table (action, binding), with a note "Custom keybinds are coming". Sorted by action.
Derived from the shortcut table in `03` §3.1.

### 3.6 Advanced

| Row              | Control                                                          |
|------------------|------------------------------------------------------------------|
| Data directory   | path (selectable) + `Open folder`                                |
| Log level        | dropdown (Info / Debug / Warn / Error)                           |
| Copy diagnostics | button → toast                                                   |
| Reset app        | danger button (type-to-confirm, calls `resetApp()` when allowed) |

`Reset app` is only enabled in `ResetRequired`, `Stopped`, `Failed`, or `SetupRequired` —
see `docs/gui-api.md`.

### 3.7 Network (backend settings)

Split into three sub-pages:

1. **Members** — admin roster (see `04` S9).
2. **Devices** — self-service devices/account.
3. **Advanced** — the generic renderer below.

---

## 4. Backend settings renderer

`config.settings: StateFlow<List<Setting>>` is already a display model. Render it directly —
no bespoke UI per field.

```kotlin
when (setting) {
    is ToggleSetting -> SwitchRow(setting.title, setting.description, setting.value) {
        scope.launch { config.update(setting.id, ConfigValue.Toggle(it)) }
    }
    is NumberSetting -> StepperRow(..., min = setting.min, max = setting.max, unit = setting.unit)
    is PeriodSetting -> DurationRow(...)   // humanised: "30s", "5m", "2h"
    is TextSetting   -> TextRow(..., isSecret = setting.isSecret)
}
```

Rules:

- Group rows by `setting.group`; render the group name as a `heading/xs` section label.
- `editable == false` → render the control disabled with the note "Controlled by the network"
  (this is exactly what `FieldSource.NETWORK` means).
- Validation is the backend's job: show `UpdateResult` failures as an inline error under the row,
  keep the previous value.
- Period values accept `30s`, `5m`, `2h`, `7d`; show a small format hint.
- Never cache settings in GUI state — always render the flow.

Because core only exposes a handful of fields today, this page will be short. That is fine: the
renderer is the deliverable, not the number of rows.

---

## 5. Settings UI mechanics

- **Live apply.** No Save/Cancel. Each change writes immediately and optimistically; on failure,
  revert and toast.
- **Search.** A field at the top filters nav sections and rows. Match on title + description,
  case-insensitive; show matching sections with only their matching rows.
- **Deep links.** `openSettings(section, subPage)` so other screens can jump to, e.g., Network →
  Devices.
- **Unsaved state.** None. The close `×` never warns.
- **Keyboard.** `Esc` closes the whole modal (not just the sub-page); `←/→` switch sub-pages.
- **Responsive.** Compact: nav becomes a full-width list, tapping a section pushes the content pane
  with a back arrow; there is no split view.

---

## 6. Captured reference: every row Discord ships

Exported from the live client into [`data/discord-settings-panes.json`](data/discord-settings-panes.json)
(labels + descriptions) and [`data/discord-settings-nav.json`](data/discord-settings-nav.json) (nav
structure). Use it as the checklist for "as configurable as Discord", not as a spec — most rows are
about features YapYap doesn't have.

**Nav (account settings):** Account · Data & Privacy · Messaging Permissions · Notifications / **Billing** (Nitro,
Server Boost, Subscriptions, Gift Inventory, Billing) / **Experience**
(Voice & Video, Appearance, Accessibility, System, Language & Time) / **Games & Apps**
(Activity Privacy, Connected Apps) / Developer · Log Out.

`Appearance` has sub-pages: Theme, App Icon, Messages, Chat Box, Search, Streamer Mode.

Rows worth knowing about (verbatim labels):

- *Appearance → Messages*: "Show embeds and link previews", "Show emoji reactions",
  "Show spoiler content", "Open threads in split view", "Show user avatars",
  "Preview emojis, mentions and markdown syntax as you type",
  "Automatically convert emoticons in your messages to emojis".
- *Accessibility*: "Text size in chat", "UI Density", "Chat Message Display",
  "Space Between Message Groups", "Saturation", "Enable High Contrast Mode", "Role Colours",
  "Enable Reduced Motion", "Always underline links" — with a live **message preview** above the
  controls so the effect is visible without leaving settings.
- *Notifications*: "Enable Desktop Notifications", per-event switches, "Enable Unread Message
  Badge", sound pack + per-sound toggles, "Disable All Notification Sounds", email rows.
- *Voice & Video*: device pickers, input/output volume, input profile, input sensitivity, noise
  suppression, echo cancellation, push-to-talk, automatic gain control, stream previews.

Two UX details worth copying regardless of feature set:

1. **Live preview.** Accessibility renders a real message row that updates as you drag sliders.
   Do this for density, text size and theme.
2. **"Related Settings" links** at the bottom of a pane, jumping to the neighbouring section.
   Cheap to build, and it removes dead ends.
