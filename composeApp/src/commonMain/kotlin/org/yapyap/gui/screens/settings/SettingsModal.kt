package org.yapyap.gui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
fun SettingsModal(session: YapSession, runtime: OrchestratorRuntime, onClose: () -> Unit) {
    val colors = yapColors
    var section by remember { mutableStateOf(Section.Account) }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxSize(0.92f)
                .clip(RoundedCornerShape(YapRadius.md))
                .background(colors.bgChat)
                .padding(0.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "Settings",
                    style = yapType.headingXl.copy(color = colors.textStrong),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    "✕",
                    style = yapType.headingMd.copy(color = colors.textMuted),
                    modifier = Modifier.clickable { onClose() }.padding(8.dp),
                )
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // Nav (253)
                Column(
                    Modifier.width(253.dp).fillMaxHeight().background(colors.bgApp)
                        .padding(12.dp),
                ) {
                    NavLabel("Account")
                    NavItem("Account", section == Section.Account) { section = Section.Account }
                    NavItem("Devices", section == Section.Devices) { section = Section.Devices }
                    NavLabel("App")
                    NavItem("Appearance", section == Section.Appearance) { section = Section.Appearance }
                    NavItem("Chat", section == Section.Chat) { section = Section.Chat }
                    NavItem("Notifications", section == Section.Notifications) { section = Section.Notifications }
                    NavItem("Keybinds", section == Section.Keybinds) { section = Section.Keybinds }
                    NavLabel("Network")
                    NavItem("Members", section == Section.Members) { section = Section.Members }
                    NavItem("Advanced", section == Section.Advanced) { section = Section.Advanced }
                }
                // Content
                Box2(Modifier.weight(1f).fillMaxHeight()) {
                    when (section) {
                        Section.Account -> AccountPane(runtime)
                        Section.Devices -> DevicesPane(session, runtime)
                        Section.Appearance -> AppearancePane(session)
                        Section.Chat -> ChatPane(session)
                        Section.Notifications -> NotificationsPane(session)
                        Section.Keybinds -> KeybindsPane()
                        Section.Members -> MembersPane(session, runtime)
                        Section.Advanced -> AdvancedPane(session, runtime)
                    }
                }
            }
        }
    }
}

@Composable
private fun Box2(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier.padding(24.dp)) { content() }
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
    BasicText(
        text,
        style = yapType.bodySmMedium.copy(color = if (selected) colors.textStrong else colors.textSubtle),
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(YapRadius.sm))
            .background(if (selected) colors.modActive else colors.bgApp.copy(alpha = 0f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText("Account ID", style = yapType.bodySmMedium.copy(color = colors.textDefault))
        }
        SelectionContainer {
            BasicText(
                a.accountId.id.take(20) + "…",
                style = yapType.bodyXs.copy(color = colors.textMuted),
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
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        BasicText(
            label,
            style = yapType.bodySmMedium.copy(color = colors.textDefault),
            modifier = Modifier.weight(1f),
        )
        BasicText(value, style = yapType.bodySm.copy(color = colors.textSubtle))
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
private fun AdvancedPane(session: YapSession, runtime: OrchestratorRuntime) {
    val colors = yapColors
    val scope = rememberCoroutineScope()
    val settings by runtime.config.settings.collectAsState(initial = emptyList())
    var showReset by remember { mutableStateOf(false) }
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
    YapButton("Reset app…", onClick = { showReset = true }, variant = YapButtonVariant.Danger)
    if (showReset) {
        var typed by remember { mutableStateOf("") }
        ConfirmDialog(
            title = "Reset the app?",
            body = "This wipes local data and returns to setup. Type \"reset\" to confirm.",
            confirmLabel = "Reset",
            onConfirm = {
                showReset = false
                // Process lifetime: reset navigates away from settings.
                session.scope.launch { runCatching { session.orchestrator?.resetApp() } }
            },
            onCancel = { showReset = false },
            requireTypedText = "reset",
            typedText = typed,
            onTypedTextChange = { typed = it },
        )
    }
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SettingsRow(title, description) { SoonChip() }
        }
    }
}

@Composable
private fun PaneTitle(text: String) {
    BasicText(
        text,
        style = yapType.bodySmMedium.copy(color = yapColors.textStrong),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

