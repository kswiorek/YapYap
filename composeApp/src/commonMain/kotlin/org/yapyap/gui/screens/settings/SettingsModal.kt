package org.yapyap.gui.screens.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
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
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.yapyap.config.*
import org.yapyap.gui.components.*
import org.yapyap.gui.copy.RefusalText
import org.yapyap.gui.identity.DeviceNames
import org.yapyap.gui.screens.setup.copyToClipboard
import org.yapyap.gui.session.ToastKind
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.OrchestratorRuntime
import org.yapyap.orchestrator.runtime.globalevent.GlobalEventOutcome
import org.yapyap.orchestrator.runtime.identity.AccountView
import org.yapyap.orchestrator.runtime.identity.DeviceView
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.IdentityStatus
import yapyap.composeapp.generated.resources.*

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
                        Box(
                            Modifier.clickable { pushed = false }.padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                FontAwesomeIcons.Solid.ChevronLeft,
                                stringResource(Res.string.desc_back),
                                Modifier.size(18.dp),
                                colors.textDefault
                            )
                        }
                        BasicText(
                            section.name,
                            style = yapType.headingSm.copy(color = colors.textStrong),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Box(
                            Modifier.clickable { onClose() }.padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                FontAwesomeIcons.Solid.Times,
                                stringResource(Res.string.desc_close_settings),
                                Modifier.size(18.dp),
                                colors.textMuted
                            )
                        }
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
                    Box(
                        Modifier.clickable { onClose() }.padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            FontAwesomeIcons.Solid.Times,
                            stringResource(Res.string.desc_close_settings),
                            Modifier.size(18.dp),
                            colors.textMuted
                        )
                    }
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
                title = stringResource(Res.string.settings_reset_title),
                body = stringResource(Res.string.settings_reset_body),
                confirmLabel = stringResource(Res.string.common_reset),
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
        NavLabel(stringResource(Res.string.settings_nav_account))
        NavItem(
            stringResource(Res.string.settings_nav_account),
            selected == Section.Account
        ) { onSelect(Section.Account) }
        NavItem(
            stringResource(Res.string.settings_nav_devices),
            selected == Section.Devices
        ) { onSelect(Section.Devices) }
        NavLabel(stringResource(Res.string.settings_nav_app))
        NavItem(
            stringResource(Res.string.settings_nav_appearance),
            selected == Section.Appearance
        ) { onSelect(Section.Appearance) }
        NavItem(stringResource(Res.string.settings_nav_chat), selected == Section.Chat) { onSelect(Section.Chat) }
        NavItem(stringResource(Res.string.settings_nav_notifications), selected == Section.Notifications) {
            onSelect(
                Section.Notifications
            )
        }
        NavItem(
            stringResource(Res.string.settings_nav_keybinds),
            selected == Section.Keybinds
        ) { onSelect(Section.Keybinds) }
        NavLabel(stringResource(Res.string.settings_nav_network))
        NavItem(
            stringResource(Res.string.settings_nav_members),
            selected == Section.Members
        ) { onSelect(Section.Members) }
        NavItem(
            stringResource(Res.string.settings_nav_advanced),
            selected == Section.Advanced
        ) { onSelect(Section.Advanced) }
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
    PaneTitle(stringResource(Res.string.settings_nav_account))
    if (local == null) {
        BasicText(stringResource(Res.string.settings_no_account), style = yapType.bodySm.copy(color = colors.textMuted))
        return
    }
    val a = local!!
    AccountRow(stringResource(Res.string.settings_display_name), a.displayName)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            stringResource(Res.string.settings_account_id),
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
    AccountRow(stringResource(Res.string.settings_network_role), a.role.name.lowercase())
    AccountRow(stringResource(Res.string.settings_status), a.status.name.lowercase())
    AccountRow(stringResource(Res.string.settings_recovery_key), stringResource(Res.string.settings_recovery_note))
}

/**
 * Labelled value with a copy icon action: selectable text plus a "⎘" button
 * that reports back via [onCopied] (toast "…copied").
 */
@Composable
private fun CopyLine(label: String, value: String, onCopied: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label,
            style = yapType.bodyXs.copy(color = yapColors.textMuted),
            modifier = Modifier.width(52.dp),
            maxLines = 1,
        )
        SelectionContainer(Modifier.weight(1f)) {
            BasicText(
                value,
                style = yapType.bodyXs.copy(color = yapColors.textMuted),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.clickable {
                copyToClipboard(value)
                onCopied()
            }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FontAwesomeIcons.Solid.Copy,
                stringResource(Res.string.desc_copy_value, label),
                Modifier.size(16.dp),
                yapColors.iconDefault
            )
        }
    }
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
    PaneTitle(stringResource(Res.string.settings_nav_devices))
    val devices = local?.devices.orEmpty()
    if (devices.isEmpty()) {
        BasicText(stringResource(Res.string.settings_no_devices), style = yapType.bodySm.copy(color = colors.textMuted))
    }
    val peerCopiedMsg = stringResource(Res.string.toast_peer_copied)
    val onionCopiedMsg = stringResource(Res.string.toast_onion_copied)
    devices.forEach { d ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    if (d.isLocal) stringResource(Res.string.settings_this_device, DeviceNames.from(d.deviceId))
                    else DeviceNames.from(d.deviceId),
                    style = yapType.bodySmMedium.copy(color = colors.textDefault),
                )
                CopyLine(
                    label = stringResource(Res.string.settings_peer_id),
                    value = d.deviceId.id,
                    onCopied = { session.showToast(peerCopiedMsg, ToastKind.Success) },
                )
                // Recovery path: the sponsor needs this device's onion + port to
                // bootstrap a recovery, so both stay visible and copiable.
                CopyLine(
                    label = stringResource(Res.string.settings_onion),
                    value = "${d.torEndpoint.onionAddress}:${d.torEndpoint.port}",
                    onCopied = { session.showToast(onionCopiedMsg, ToastKind.Success) },
                )
            }
            Spacer(Modifier.width(12.dp))
            YapButton(
                stringResource(Res.string.settings_remove),
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
    PaneTitle(stringResource(Res.string.settings_nav_appearance))
    SettingsRow(stringResource(Res.string.settings_theme), stringResource(Res.string.settings_theme_desc)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeChip(
                label = stringResource(Res.string.settings_theme_dark),
                selected = prefs.appearance.theme == "dark",
                onClick = {
                    session.prefsController.update {
                        it.copy(appearance = it.appearance.copy(theme = "dark"))
                    }
                },
            )
            ThemeChip(
                label = stringResource(Res.string.settings_theme_light),
                selected = prefs.appearance.theme == "light",
                onClick = {
                    session.prefsController.update {
                        it.copy(appearance = it.appearance.copy(theme = "light"))
                    }
                },
            )
            ThemeChip(
                label = stringResource(Res.string.settings_theme_system),
                selected = prefs.appearance.theme == "system",
                onClick = {
                    session.prefsController.update {
                        it.copy(appearance = it.appearance.copy(theme = "system"))
                    }
                },
            )
        }
    }
    SettingsRow(stringResource(Res.string.settings_msg_display), stringResource(Res.string.settings_msg_display_desc)) {
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
    SettingsRow(
        stringResource(Res.string.settings_sidebar),
        stringResource(Res.string.settings_sidebar_unit, prefs.appearance.sidebarWidth)
    ) {
        BasicText(
            stringResource(Res.string.settings_sidebar_range),
            style = yapType.bodySm.copy(color = yapColors.textMuted)
        )
    }
    SettingsRow(stringResource(Res.string.settings_zoom), stringResource(Res.string.settings_zoom_desc)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (preset in listOf(75, 100, 125, 150, 175, 200)) {
                ZoomChip(
                    label = "$preset%",
                    selected = prefs.appearance.uiZoom == preset,
                    onClick = {
                        session.prefsController.update {
                            it.copy(appearance = it.appearance.copy(uiZoom = preset))
                        }
                    },
                )
            }
        }
    }
    SoonRow(stringResource(Res.string.settings_accent), stringResource(Res.string.settings_accent_desc))
}

/** Theme selector chip: same geometry as [ZoomChip], separate name for intent. */
@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    ZoomChip(label, selected, onClick)
}

@Composable
private fun ChatPane(session: YapSession) {
    val prefs by session.prefsController.prefs.collectAsState()
    PaneTitle(stringResource(Res.string.settings_nav_chat))
    SettingsRow(stringResource(Res.string.settings_send_enter), stringResource(Res.string.settings_send_enter_desc)) {
        YapSwitch(prefs.chat.sendWithEnter, { v ->
            session.prefsController.update { it.copy(chat = it.chat.copy(sendWithEnter = v)) }
        })
    }
    SettingsRow(stringResource(Res.string.settings_char_count), stringResource(Res.string.settings_char_count_desc)) {
        YapSwitch(prefs.chat.showCharCount, { v ->
            session.prefsController.update { it.copy(chat = it.chat.copy(showCharCount = v)) }
        })
    }
    SettingsRow(
        stringResource(Res.string.settings_preview_typing),
        stringResource(Res.string.settings_preview_typing_desc)
    ) { SoonChip() }
}

@Composable
private fun NotificationsPane(session: YapSession) {
    val prefs by session.prefsController.prefs.collectAsState()
    val n = prefs.notifications
    PaneTitle(stringResource(Res.string.settings_nav_notifications))
    SettingsRow(
        stringResource(Res.string.settings_desktop_notif),
        stringResource(Res.string.settings_desktop_notif_desc)
    ) {
        YapSwitch(n.desktopNotifications, { v ->
            session.prefsController.update { it.copy(notifications = n.copy(desktopNotifications = v)) }
        })
    }
    SettingsRow(stringResource(Res.string.settings_sound), stringResource(Res.string.settings_sound_desc)) {
        YapSwitch(n.sound, { v ->
            session.prefsController.update { it.copy(notifications = n.copy(sound = v)) }
        })
    }
    SettingsRow(stringResource(Res.string.settings_badges), stringResource(Res.string.settings_badges_desc)) {
        YapSwitch(n.showMentionBadges, { v ->
            session.prefsController.update { it.copy(notifications = n.copy(showMentionBadges = v)) }
        })
    }
    SoonRow(stringResource(Res.string.settings_push), stringResource(Res.string.settings_push_desc))
}

@Composable
private fun KeybindsPane() {
    PaneTitle(stringResource(Res.string.settings_nav_keybinds))
    listOf(
        "Ctrl/Cmd+K" to stringResource(Res.string.settings_keybind_switcher),
        "Ctrl/Cmd+N" to stringResource(Res.string.settings_keybind_new_room),
        "Esc" to stringResource(Res.string.settings_keybind_close),
        "Alt+↑/↓" to stringResource(Res.string.settings_keybind_prev_next),
        "Ctrl/Cmd+," to stringResource(Res.string.settings_keybind_settings),
        "Shift+Esc" to stringResource(Res.string.settings_keybind_mark_read),
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
        stringResource(Res.string.settings_keybinds_note),
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
    PaneTitle(stringResource(Res.string.settings_nav_members))
    if (!isAdmin) {
        BasicText(
            stringResource(Res.string.settings_admin_only),
            style = yapType.bodySm.copy(color = yapColors.textMuted),
        )
    }
    var expanded by remember { mutableStateOf<AccountId?>(null) }
    var banTarget by remember { mutableStateOf<AccountView?>(null) }
    var confirmStepDown by remember { mutableStateOf(false) }

    val grantedMsg = stringResource(Res.string.toast_admin_granted)
    val revokedMsg = stringResource(Res.string.toast_admin_revoked)
    val bannedMsg = stringResource(Res.string.toast_banned)
    val deviceRemovedMsg = stringResource(Res.string.toast_device_removed)
    val steppedDownMsg = stringResource(Res.string.toast_stepped_down)
    val opFailedMsg = stringResource(Res.string.toast_op_failed)
    fun adminOp(op: suspend () -> GlobalEventOutcome, success: String) {
        scope.launch {
            val res = runCatching { op() }.getOrElse {
                session.showToast(it.message ?: opFailedMsg, ToastKind.Error)
                return@launch
            }
            when (res) {
                is GlobalEventOutcome.Published ->
                    session.showToast(success, ToastKind.Success)

                is GlobalEventOutcome.Refused ->
                    session.showToast(RefusalText.global(res.reason), ToastKind.Error)
            }
        }
    }

    accounts.forEach { a ->
        val active = a.status == IdentityStatus.ACTIVE
        val own = a.isLocal
        val owner = a.role == AccountRole.OWNER
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(a.accountId, a.displayName, size = 32.dp)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    BasicText(a.displayName, style = yapType.bodySmMedium.copy(color = colors.textDefault))
                    BasicText(
                        stringResource(
                            Res.string.settings_member_line,
                            a.role.name.lowercase(),
                            a.status.name.lowercase(),
                            pluralStringResource(Res.plurals.member_device_count, a.devices.size, a.devices.size),
                        ),
                        style = yapType.bodyXs.copy(color = colors.textMuted),
                    )
                }
                if (a.devices.isNotEmpty()) {
                    Box(
                        Modifier.clickable {
                            expanded = if (expanded == a.accountId) null else a.accountId
                        }.padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (expanded == a.accountId) FontAwesomeIcons.Solid.ChevronUp
                            else FontAwesomeIcons.Solid.ChevronDown,
                            stringResource(Res.string.desc_expand_devices),
                            Modifier.size(16.dp),
                            colors.iconDefault
                        )
                    }
                }
            }
            // Admin actions: grant/revoke need ACTIVE MEMBER/ADMIN; ban needs
            // ACTIVE non-owner; self-service paths own the local rows (except
            // the legal self step-down). Refusals stay as toasts, never throws.
            if (isAdmin && active) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        own && a.role == AccountRole.ADMIN -> YapButton(
                            stringResource(Res.string.settings_step_down),
                            onClick = { confirmStepDown = true },
                            variant = YapButtonVariant.Secondary,
                            compact = true,
                        )

                        !own && !owner && a.role == AccountRole.MEMBER -> YapButton(
                            stringResource(Res.string.settings_make_admin),
                            onClick = {
                                adminOp({ runtime.admin.grantAdmin(a.accountId) }, grantedMsg)
                            },
                            variant = YapButtonVariant.Secondary,
                            compact = true,
                        )

                        !own && !owner && a.role == AccountRole.ADMIN -> YapButton(
                            stringResource(Res.string.settings_remove_admin),
                            onClick = {
                                adminOp({ runtime.admin.revokeAdmin(a.accountId) }, revokedMsg)
                            },
                            variant = YapButtonVariant.Secondary,
                            compact = true,
                        )
                    }
                    if (!own && !owner) {
                        YapButton(
                            stringResource(Res.string.settings_ban),
                            onClick = { banTarget = a },
                            variant = YapButtonVariant.Danger,
                            compact = true,
                        )
                    }
                }
            }
            // Device inventory with per-device admin removal (other accounts'
            // non-owner devices only — own devices live in DevicesPane).
            if (expanded == a.accountId) {
                a.devices.sortedBy { it.deviceId.id }.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                DeviceNames.from(d.deviceId),
                                style = yapType.bodySm.copy(color = colors.textDefault),
                            )
                            BasicText(
                                deviceLine(d),
                                style = yapType.bodyXs.copy(color = colors.textMuted),
                            )
                        }
                        if (isAdmin && !own && !owner) {
                            YapButton(
                                stringResource(Res.string.settings_remove),
                                onClick = {
                                    adminOp(
                                        { runtime.admin.removeDevice(d.deviceId) },
                                        deviceRemovedMsg
                                    )
                                },
                                variant = YapButtonVariant.Danger,
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
    }
    // Destructive confirms: ban and self step-down.
    banTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(Res.string.settings_ban_title, target.displayName),
            body = stringResource(Res.string.settings_ban_body, target.displayName),
            confirmLabel = stringResource(Res.string.common_ban),
            onConfirm = {
                banTarget = null
                scope.launch {
                    val res = runCatching { runtime.admin.removeAccount(target.accountId) }
                        .getOrElse {
                            session.showToast(it.message ?: opFailedMsg, ToastKind.Error)
                            return@launch
                        }
                    when (res) {
                        is GlobalEventOutcome.Published ->
                            session.showToast(bannedMsg, ToastKind.Success)

                        is GlobalEventOutcome.Refused ->
                            session.showToast(RefusalText.global(res.reason), ToastKind.Error)
                    }
                }
            },
            onCancel = { banTarget = null },
        )
    }
    if (confirmStepDown) {
        ConfirmDialog(
            title = stringResource(Res.string.settings_step_down_title),
            body = stringResource(Res.string.settings_step_down_body),
            confirmLabel = stringResource(Res.string.settings_step_down_confirm),
            onConfirm = {
                confirmStepDown = false
                localAccountId(accounts)?.let { self ->
                    adminOp({ runtime.admin.revokeAdmin(self) }, steppedDownMsg)
                }
            },
            onCancel = { confirmStepDown = false },
        )
    }
}

/** Local account id or null when the roster hasn't loaded yet. */
private fun localAccountId(accounts: List<AccountView>): AccountId? =
    accounts.firstOrNull { it.isLocal }?.accountId

@Composable
private fun deviceLine(d: DeviceView): String {
    val base = d.deviceType.name.lowercase() + " · " + d.status.name.lowercase()
    return if (d.provisional) {
        base + " · " + stringResource(Res.string.settings_provisional)
    } else base
}

@Composable
private fun AdvancedPane(session: YapSession, runtime: OrchestratorRuntime, onResetRequest: () -> Unit) {
    val colors = yapColors
    val scope = rememberCoroutineScope()
    val settings by runtime.config.settings.collectAsState(initial = emptyList())
    PaneTitle(stringResource(Res.string.settings_nav_advanced))
    AccountRow(stringResource(Res.string.settings_data_dir), session.dataDirectory.toString())
    if (settings.isEmpty()) {
        BasicText(
            stringResource(Res.string.settings_no_backend),
            style = yapType.bodySm.copy(color = yapColors.textMuted),
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
                    val result = runCatching { runtime.config.update(s.id, v) }.getOrElse {
                        session.showToast(it.message ?: "Couldn't update.", ToastKind.Error)
                        return@launch
                    }
                    // update() returns refusals as values, never exceptions.
                    if (result is UpdateResult.Failure) {
                        session.showToast(result.reason, ToastKind.Error)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    YapButton(
        stringResource(Res.string.settings_reset_app),
        onClick = onResetRequest,
        variant = YapButtonVariant.Danger
    )
}

@Composable
private fun BackendRow(setting: Setting, onUpdate: (ConfigValue?) -> Unit) {
    when (setting) {
        is ToggleSetting -> SettingsRow(setting.title, setting.description) {
            YapSwitch(
                setting.value,
                { onUpdate(ConfigValue.Toggle(it)) },
                enabled = setting.editable,
            )
        }

        is TextSetting -> {
            if (!setting.editable) {
                SettingsRow(setting.title, setting.description) {
                    BasicText(
                        if (setting.isSecret) stringResource(Res.string.settings_secret_mask) else setting.value.ifBlank {
                            stringResource(
                                Res.string.settings_empty_value
                            )
                        },
                        style = yapType.bodySm.copy(color = yapColors.textMuted),
                    )
                }
            } else {
                EditableTextRow(
                    title = setting.title,
                    description = setting.description,
                    initial = setting.value,
                    isSecret = setting.isSecret,
                    onSave = { onUpdate(ConfigValue.Text(it)) },
                    onReset = { onUpdate(null) },
                )
            }
        }

        is NumberSetting -> {
            if (!setting.editable) {
                SettingsRow(
                    setting.title,
                    setting.description + (setting.unit?.let { " ($it)" } ?: ""),
                ) {
                    BasicText(
                        setting.value.toString(),
                        style = yapType.bodySm.copy(color = yapColors.textMuted),
                    )
                }
            } else {
                EditableNumberRow(
                    title = setting.title,
                    description = setting.description + (setting.unit?.let { " ($it)" } ?: ""),
                    initial = setting.value,
                    min = setting.min,
                    max = setting.max,
                    onSave = { onUpdate(ConfigValue.Number(it)) },
                    onReset = { onUpdate(null) },
                )
            }
        }

        is PeriodSetting -> {
            if (!setting.editable) {
                SettingsRow(setting.title, setting.description) {
                    BasicText(
                        setting.value.toString(),
                        style = yapType.bodySm.copy(color = yapColors.textMuted),
                    )
                }
            } else {
                EditablePeriodRow(
                    title = setting.title,
                    description = setting.description,
                    initial = setting.value,
                    onSave = { onUpdate(ConfigValue.Period(it)) },
                    onReset = { onUpdate(null) },
                )
            }
        }
    }
}

/** Small "back to default" affordance: clears the user override (update(id, null)). */
@Composable
private fun ResetDefaultLink(onReset: () -> Unit) {
    BasicText(
        stringResource(Res.string.common_reset),
        style = yapType.bodyXs.copy(color = yapColors.textLink),
        modifier = Modifier.clickable { onReset() }.padding(4.dp),
    )
}

@Composable
private fun EditableTextRow(
    title: String,
    description: String,
    initial: String,
    isSecret: Boolean,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val dirty = text != initial
    fun save() {
        if (dirty) onSave(text)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).onEnterConfirm(enabled = dirty) { save() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                title,
                style = yapType.bodySmMedium.copy(color = yapColors.textDefault),
                modifier = Modifier.weight(1f),
            )
            ResetDefaultLink(onReset)
            YapButton(
                stringResource(Res.string.common_save),
                onClick = ::save,
                enabled = dirty,
                variant = YapButtonVariant.Secondary
            )
        }
        BasicText(
            description,
            style = yapType.bodyXs.copy(color = yapColors.textMuted),
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        YapTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = title,
            visualTransformation = if (isSecret) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
        )
    }
}

@Composable
private fun EditableNumberRow(
    title: String,
    description: String,
    initial: Long,
    min: Long?,
    max: Long?,
    onSave: (Long) -> Unit,
    onReset: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial.toString()) }
    val parsed = text.toLongOrNull()
    val rangeError = when {
        parsed == null -> stringResource(Res.string.settings_number_error)
        min != null && parsed < min -> stringResource(Res.string.settings_number_min, min)
        max != null && parsed > max -> stringResource(Res.string.settings_number_max, max)
        else -> null
    }
    val dirty = parsed != null && parsed != initial && rangeError == null
    fun save() {
        if (dirty) onSave(parsed!!)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).onEnterConfirm(enabled = dirty) { save() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                title,
                style = yapType.bodySmMedium.copy(color = yapColors.textDefault),
                modifier = Modifier.weight(1f),
            )
            ResetDefaultLink(onReset)
            YapButton(
                stringResource(Res.string.common_save),
                onClick = ::save,
                enabled = dirty,
                variant = YapButtonVariant.Secondary
            )
        }
        BasicText(
            description,
            style = yapType.bodyXs.copy(color = yapColors.textMuted),
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        YapTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() || (c == '-' && it.startsWith('-')) }.take(19) },
            placeholder = initial.toString(),
            isError = rangeError != null,
        )
        if (rangeError != null) InlineError(rangeError)
    }
}

@Composable
private fun EditablePeriodRow(
    title: String,
    description: String,
    initial: kotlin.time.Duration,
    onSave: (kotlin.time.Duration) -> Unit,
    onReset: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial.toString()) }
    val parsed = remember(text) { parseDurationLoose(text) }
    val dirty = parsed != null && parsed != initial
    fun save() {
        val p = parsed
        if (p != null && p != initial) onSave(p)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).onEnterConfirm(enabled = dirty) { save() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                title,
                style = yapType.bodySmMedium.copy(color = yapColors.textDefault),
                modifier = Modifier.weight(1f),
            )
            ResetDefaultLink(onReset)
            YapButton(
                stringResource(Res.string.common_save),
                onClick = ::save,
                enabled = dirty,
                variant = YapButtonVariant.Secondary
            )
        }
        BasicText(
            stringResource(Res.string.settings_period_hint, description),
            style = yapType.bodyXs.copy(color = yapColors.textMuted),
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        YapTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = initial.toString(),
            isError = text.isNotBlank() && parsed == null,
        )
        if (text.isNotBlank() && parsed == null) InlineError(stringResource(Res.string.settings_period_error))
    }
}

/** Accepts "90s"/"5m"/"2h" via Duration.parse, plus a bare number meaning seconds. */
private fun parseDurationLoose(raw: String): kotlin.time.Duration? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    runCatching { kotlin.time.Duration.parse(t) }.getOrNull()?.let { return it }
    val seconds = t.toLongOrNull() ?: return null
    if (seconds < 0) return null
    return kotlin.time.Duration.parse("${seconds}s")
}

@Composable
private fun ZoomChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(YapRadius.sm))
            .background(if (selected) yapColors.brand else yapColors.modSubtle)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = yapType.bodyXsMedium.copy(color = yapColors.textStrong),
            maxLines = 1,
        )
    }
}

@Composable
private fun SoonRow(
    title: String,
    description: String
) {    // Previously Row { Column(weight(1f)) { SettingsRow(...) } }: the extra nesting
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

