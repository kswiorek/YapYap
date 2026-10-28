package org.yapyap.gui.screens.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.yapyap.config.*
import org.yapyap.gui.components.*
import org.yapyap.gui.copy.RefusalText
import org.yapyap.gui.identity.DeviceNames
import org.yapyap.gui.session.ToastKind
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.OrchestratorRuntime
import org.yapyap.orchestrator.runtime.globalevent.GlobalEventOutcome

private enum class Section {
    Account, Devices, Appearance, Chat, Notifications, Keybinds, Members, Advanced,
}

@Composable
fun SettingsModal(
    session: YapSession,
    runtime: OrchestratorRuntime,
    onClose: () -> Unit,
) {
    val colors = yapColors
    var section by remember { mutableStateOf(Section.Account) }
    // Compact (<600): single pane per W9 — nav list, tap pushes content with a back bar.
    var pushed by remember { mutableStateOf(false) }
    // Reset confirm lives here, not in AdvancedPane: overlays need a bounded scope
    // and the pane sits inside scrollable content (unbounded height).
    var showReset by remember { mutableStateOf(false) }
    var resetTyped by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // In-window overlay, NOT a Dialog. Dialog opens a blocking OS window: the parent
    // can't resize while it's open and the panel sizes itself once. This Box lives in
    // the main window, so BoxWithConstraints measures the live window every
    // recomposition and the panel tracks resizes automatically.
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(colors.scrim)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.key == Key.Escape) {
                    onClose()
                    true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        // W8 geometry, re-evaluated on every resize: min(1400, width-80).
        // Short windows (<600 high) go full-bleed instead of a centred card.
        val fullBleed = maxHeight < 600.dp
        val panelWidth = (maxWidth - 80.dp).coerceIn(320.dp, 1400.dp)
        val panelHeight = (maxHeight - 80.dp).coerceIn(400.dp, 945.dp)
        val panelModifier = if (fullBleed) {
            Modifier.fillMaxSize().background(colors.bgChat)
        } else {
            Modifier.width(panelWidth).height(panelHeight)
                .clip(RoundedCornerShape(YapRadius.md))
                .background(colors.bgChat)
        }
        if (maxWidth < 600.dp) {
            Column(panelModifier) {
                if (!pushed) {
                    SettingsNav(
                        selected = section,
                        onSelect = {
                            section = it
                            pushed = true
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "‹",
                            style = yapType.headingLg.copy(color = colors.textDefault),
                            modifier = Modifier.clickable { pushed = false }.padding(8.dp),
                        )
                        BasicText(
                            section.name,
                            style = yapType.headingSm.copy(color = colors.textStrong),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        BasicText(
                            "✕",
                            style = yapType.headingMd.copy(color = colors.textMuted),
                            modifier = Modifier.clickable { onClose() }.padding(8.dp),
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(colors.borderNormal),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        SettingsPane(
                            section,
                            session,
                            runtime,
                            onResetRequest = { showReset = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        } else {
            // Full-window modal (W8): fixed header, weight(1f) body row.
            Column(panelModifier) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        "Settings",
                        style = yapType.headingXl.copy(color = colors.textStrong),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    BasicText(
                        "✕",
                        style = yapType.headingMd.copy(color = colors.textMuted),
                        modifier = Modifier.clickable { onClose() }.padding(8.dp),
                    )
                }
                // Body row: fixed nav + flex content (`display: flex`).
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    SettingsNav(
                        selected = section,
                        onSelect = { section = it },
                        modifier = Modifier.width(253.dp).fillMaxHeight(),
                    )
                    // Content (`flex: 1`, scrolls). Max-width 740 centred like the web.
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        SettingsPane(
                            section,
                            session,
                            runtime,
                            onResetRequest = { showReset = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        // Reset confirm at overlay level (bounded scope, above the panel).
        if (showReset) {
            ConfirmDialog(
                title = "Reset the app?",
                body = "This wipes local data and returns to setup. Type \"reset\" to confirm.",
                confirmLabel = "Reset",
                onConfirm = {
                    showReset = false
                    // Stops first (reset refuses Running), wipes, then boots
                    // back into SetupRequired. Failures surface as toasts.
                    session.resetAndReboot()
                },
                onCancel = { showReset = false },
                requireTypedText = "reset",
                typedText = resetTyped,
                onTypedTextChange = { resetTyped = it },
            )
        }
    }
}

@Composable
private fun SettingsNav(
    selected: Section,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Fixed-width nav with its own scroll so short windows don't clip.
    Column(
        modifier.background(yapColors.bgApp)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        NavLabel("Account")
        NavItem("Account", selected == Section.Account) { onSelect(Section.Account) }
        NavItem("Devices", selected == Section.Devices) { onSelect(Section.Devices) }
        NavLabel("App")
        NavItem("Appearance", selected == Section.Appearance) { onSelect(Section.Appearance) }
        NavItem("Chat", selected == Section.Chat) { onSelect(Section.Chat) }
        NavItem("Notifications", selected == Section.Notifications) { onSelect(Section.Notifications) }
        NavItem("Keybinds", selected == Section.Keybinds) { onSelect(Section.Keybinds) }
        NavLabel("Network")
        NavItem("Members", selected == Section.Members) { onSelect(Section.Members) }
        NavItem("Advanced", selected == Section.Advanced) { onSelect(Section.Advanced) }
    }
}

@Composable
private fun SettingsPane(
    section: Section,
    session: YapSession,
    runtime: OrchestratorRuntime,
    onResetRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = 740.dp)) {
            when (section) {
                Section.Account -> AccountPane(runtime)
                Section.Devices -> DevicesPane(session, runtime)
                Section.Appearance -> AppearancePane(session)
                Section.Chat -> ChatPane(session)
                Section.Notifications -> NotificationsPane(session)
                Section.Keybinds -> KeybindsPane()
                Section.Members -> MembersPane(session, runtime)
                Section.Advanced -> AdvancedPane(session, runtime, onResetRequest)
            }
        }
    }
}

@Composable
private fun NavLabel(text: String) {
    BasicText(
        text.uppercase(),
        style = yapType.headingXs.copy(color = yapColors.textMuted),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun NavItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = yapColors
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(YapRadius.sm))
            .background(if (selected) colors.modActive else colors.bgApp.copy(alpha = 0f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BasicText(
            text,
            style = yapType.bodySmMedium.copy(color = if (selected) colors.textStrong else colors.textSubtle),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

// ---- Panes ----

@Composable
private fun AccountPane(runtime: OrchestratorRuntime) {
    val colors = yapColors
    val local by runtime.identity.localAccount.collectAsState(initial = null)
    PaneTitle("Account")
    if (local == null) {
        BasicText("No account yet.", style = yapType.bodySm.copy(color = colors.textMuted))
        return
    }
    val a = local!!
    AccountRow("Display name", a.displayName)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            "Account ID",
            style = yapType.bodySmMedium.copy(color = colors.textDefault),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        SelectionContainer {
            BasicText(
                a.accountId.id.take(20) + "…",
                style = yapType.bodyXs.copy(color = colors.textMuted),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
    AccountRow("Network role", a.role.name.lowercase())
    AccountRow("Status", a.status.name.lowercase())
    AccountRow("Recovery key", "Shown once at setup — store it safely")
}

@Composable
private fun AccountRow(label: String, value: String) {
    val colors = yapColors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            label,
            style = yapType.bodySmMedium.copy(color = colors.textDefault),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        BasicText(
            value,
            style = yapType.bodySm.copy(color = colors.textSubtle),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DevicesPane(session: YapSession, runtime: OrchestratorRuntime) {
    val colors = yapColors
    val scope = rememberCoroutineScope()
    val local by runtime.identity.localAccount.collectAsState(initial = null)
    PaneTitle("Devices")
    val devices = local?.devices.orEmpty()
    if (devices.isEmpty()) {
        BasicText("No devices.", style = yapType.bodySm.copy(color = colors.textMuted))
    }
    devices.forEach { d ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    DeviceNames.from(d.deviceId) + if (d.isLocal) " (this device)" else "",
                    style = yapType.bodySmMedium.copy(color = colors.textDefault),
                )
                SelectionContainer {
                    BasicText(
                        d.deviceId.id,
                        style = yapType.bodyXs.copy(color = colors.textMuted),
                    )
                }
            }
            YapButton(
                "Remove",
                onClick = {
                    scope.launch {
                        val res = runCatching {
                            if (d.isLocal) runtime.account.removeThisDevice()
                            else runtime.account.removeOwnDevice(d.deviceId)
                        }.getOrElse {
                            session.showToast(it.message ?: "Couldn't remove.", ToastKind.Error)
                            return@launch
                        }
                        if (res is GlobalEventOutcome.Refused) {
                            session.showToast(RefusalText.global(res.reason), ToastKind.Error)
                        }
                    }
                },
                variant = YapButtonVariant.Danger,
            )
        }
    }
}

@Composable
private fun AppearancePane(session: YapSession) {
    val prefs by session.prefsController.prefs.collectAsState()
    PaneTitle("Appearance")
    SettingsRow("Theme", "Dark only for now") {
        BasicText("Dark", style = yapType.bodySm.copy(color = yapColors.textDefault))
    }
    SettingsRow("Chat message display", "Cozy or compact") {
        BasicText(
            prefs.appearance.messageDisplay,
            style = yapType.bodySm.copy(color = yapColors.textDefault),
            modifier = Modifier.clickable {
                session.prefsController.update {
                    it.copy(
                        appearance = it.appearance.copy(
                            messageDisplay = if (it.appearance.messageDisplay == "cozy") "compact" else "cozy",
                        ),
                    )
                }
            }.padding(4.dp),
        )
    }
    SettingsRow("Sidebar width", "${prefs.appearance.sidebarWidth} dp") {
        BasicText("240–360", style = yapType.bodySm.copy(color = yapColors.textMuted))
    }
    SoonRow("Light theme", "A second palette, coming later")
    SoonRow("Custom accent", "Blurple for now")
}

@Composable
private fun ChatPane(session: YapSession) {
    val prefs by session.prefsController.prefs.collectAsState()
    PaneTitle("Chat")
    SettingsRow("Send with Enter", "Off = Ctrl/Cmd+Enter") {
        YapSwitch(prefs.chat.sendWithEnter, { v ->
            session.prefsController.update { it.copy(chat = it.chat.copy(sendWithEnter = v)) }
        })
    }
    SettingsRow("Show character count", "Byte budget in the composer") {
        YapSwitch(prefs.chat.showCharCount, { v ->
            session.prefsController.update { it.copy(chat = it.chat.copy(showCharCount = v)) }
        })
    }
    SettingsRow("Preview while typing", "Markdown live preview") { SoonChip() }
}

@Composable
private fun NotificationsPane(session: YapSession) {
    val prefs by session.prefsController.prefs.collectAsState()
    val n = prefs.notifications
    PaneTitle("Notifications")
    SettingsRow("Desktop notifications", "Mention and message alerts") {
        YapSwitch(n.desktopNotifications, { v ->
            session.prefsController.update { it.copy(notifications = n.copy(desktopNotifications = v)) }
        })
    }
    SettingsRow("Notification sound", "Bundled sound") {
        YapSwitch(n.sound, { v ->
            session.prefsController.update { it.copy(notifications = n.copy(sound = v)) }
        })
    }
    SettingsRow("Show mention badges", "Red pills in the room list") {
        YapSwitch(n.showMentionBadges, { v ->
            session.prefsController.update { it.copy(notifications = n.copy(showMentionBadges = v)) }
        })
    }
    SoonRow("Push notifications", "Needs the mobile push pipeline")
}

@Composable
private fun KeybindsPane() {
    PaneTitle("Keybinds")
    listOf(
        "Ctrl/Cmd+K" to "Quick switcher",
        "Ctrl/Cmd+N" to "New room",
        "Esc" to "Close / clear",
        "Alt+↑/↓" to "Previous / next room",
        "Ctrl/Cmd+," to "Settings",
        "Shift+Esc" to "Mark room read",
    ).forEach { (keys, action) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            BasicText(
                keys,
                style = yapType.bodySmMedium.copy(color = yapColors.textDefault),
                modifier = Modifier.weight(1f),
            )
            BasicText(action, style = yapType.bodySm.copy(color = yapColors.textSubtle))
        }
    }
    BasicText(
        "Custom keybinds are coming in a later build.",
        style = yapType.bodyXs.copy(color = yapColors.textMuted),
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun MembersPane(session: YapSession, runtime: OrchestratorRuntime) {
    val colors = yapColors
    val scope = rememberCoroutineScope()
    val accounts by runtime.identity.accounts.collectAsState(initial = emptyList())
    val isAdmin by runtime.admin.localIsAdmin.collectAsState(initial = false)
    PaneTitle("Members")
    if (!isAdmin) {
        BasicText(
            "Only admins can manage members.",
            style = yapType.bodySm.copy(color = colors.textMuted),
        )
    }
    accounts.forEach { a ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(a.accountId, a.displayName, size = 32.dp)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                BasicText(a.displayName, style = yapType.bodySmMedium.copy(color = colors.textDefault))
                BasicText(
                    "${a.role.name.lowercase()} · ${a.status.name.lowercase()} · ${a.devices.size} devices",
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                )
            }
            if (isAdmin && !a.isLocal) {
                YapButton(
                    "Ban",
                    onClick = {
                        scope.launch {
                            val res = runCatching { runtime.admin.removeAccount(a.accountId) }
                                .getOrElse {
                                    session.showToast(it.message ?: "Failed.", ToastKind.Error)
                                    return@launch
                                }
                            if (res is GlobalEventOutcome.Refused) {
                                session.showToast(RefusalText.global(res.reason), ToastKind.Error)
                            }
                        }
                    },
                    variant = YapButtonVariant.Danger,
                )
            }
        }
    }
}

@Composable
private fun AdvancedPane(session: YapSession, runtime: OrchestratorRuntime, onResetRequest: () -> Unit) {
    val colors = yapColors
    val scope = rememberCoroutineScope()
    val settings by runtime.config.settings.collectAsState(initial = emptyList())
    PaneTitle("Advanced")
    AccountRow("Data directory", session.dataDirectory.toString())
    if (settings.isEmpty()) {
        BasicText(
            "No backend settings exposed.",
            style = yapType.bodySm.copy(color = colors.textMuted),
        )
    }
    val grouped = remember(settings) { settings.groupBy { it.group } }
    grouped.forEach { (group, rows) ->
        BasicText(
            group.uppercase(),
            style = yapType.headingXs.copy(color = colors.textMuted),
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        rows.forEach { s ->
            BackendRow(s) { v ->
                scope.launch {
                    runCatching { runtime.config.update(s.id, v) }.onFailure {
                        session.showToast(it.message ?: "Couldn't update.", ToastKind.Error)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    YapButton("Reset app…", onClick = onResetRequest, variant = YapButtonVariant.Danger)
}

@Composable
private fun BackendRow(setting: Setting, onUpdate: (ConfigValue?) -> Unit) {
    val colors = yapColors
    when (setting) {
        is ToggleSetting -> SettingsRow(setting.title, setting.description) {
            YapSwitch(
                setting.value,
                { onUpdate(ConfigValue.Toggle(it)) },
                enabled = setting.editable,
            )
        }

        is TextSetting -> SettingsRow(setting.title, setting.description) {
            BasicText(
                setting.value.ifBlank { "—" },
                style = yapType.bodySm.copy(
                    color = if (setting.editable) colors.textDefault else colors.textMuted,
                ),
            )
        }

        is NumberSetting -> SettingsRow(
            setting.title,
            setting.description + (setting.unit?.let { " ($it)" } ?: ""),
        ) {
            BasicText(
                setting.value.toString(),
                style = yapType.bodySm.copy(
                    color = if (setting.editable) colors.textDefault else colors.textMuted,
                ),
            )
        }

        is PeriodSetting -> SettingsRow(setting.title, setting.description) {
            BasicText(
                setting.value.toString(),
                style = yapType.bodySm.copy(
                    color = if (setting.editable) colors.textDefault else colors.textMuted,
                ),
            )
        }
    }
}

@Composable
private fun SoonRow(title: String, description: String) {
    // Previously Row { Column(weight(1f)) { SettingsRow(...) } }: the extra nesting
    // gave the inner Row zero width so title and chip overlapped. One flex row is enough.
    SettingsRow(title, description, modifier = Modifier.fillMaxWidth()) { SoonChip() }
}

@Composable
private fun PaneTitle(text: String) {
    BasicText(
        text,
        style = yapType.headingXs.copy(color = yapColors.textStrong),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

