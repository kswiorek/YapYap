package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Home
import compose.icons.fontawesomeicons.solid.Plus
import compose.icons.fontawesomeicons.solid.PlusCircle
import compose.icons.fontawesomeicons.solid.Users
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.components.*
import org.yapyap.gui.copy.RefusalText
import org.yapyap.gui.screens.createroom.CreateRoomDialog
import org.yapyap.gui.screens.quickswitcher.QuickSwitcher
import org.yapyap.gui.screens.settings.SettingsModal
import org.yapyap.gui.screens.setup.Base64Encoder
import org.yapyap.gui.screens.setup.NewcomerStatusBanner
import org.yapyap.gui.screens.setup.SponsorDialog
import org.yapyap.gui.screens.setup.copyToClipboard
import org.yapyap.gui.session.ToastKind
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.state.ReadStateStore
import org.yapyap.gui.state.RoomsController
import org.yapyap.gui.state.roomKey
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.LocalTransportStatus
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.orchestrator.runtime.OrchestratorRuntime
import org.yapyap.orchestrator.runtime.identity.AvailabilityLabel
import org.yapyap.protocol.RoomId
import yapyap.composeapp.generated.resources.* // Res + generated string accessors
import kotlin.time.Clock

private enum class RailDestination { Home, Friends }

@Composable
fun MainShell(
    session: YapSession,
    runtime: OrchestratorRuntime,
    transportStatus: LocalTransportStatus,
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    val scope = rememberCoroutineScope()
    val prefs by session.prefsController.prefs.collectAsState()
    val roomsController = remember(runtime) { RoomsController(scope, runtime, session.prefsController) }
    LaunchedEffect(runtime) { roomsController.start() }
    val items by roomsController.items.collectAsState()
    val accounts by runtime.identity.accounts.collectAsState(initial = emptyList())
    val local by runtime.identity.localAccount.collectAsState(initial = null)
    val names = remember(accounts) { accounts.associate { it.accountId to it.displayName } }
    val availability = remember(accounts) {
        accounts.associate { it.accountId to it.availability.label }
    }

    var selectedId by remember { mutableStateOf<RoomId?>(null) }
    var destination by remember { mutableStateOf(RailDestination.Home) }
    // Member panel visibility: manual toggle wins, but it resets whenever the
    // window crosses the wide/narrow breakpoint — shrinking hides the panel by
    // default (reopenable via the members button), growing shows it by default.
    var rightOverride by remember { mutableStateOf<Boolean?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    var showSponsor by remember { mutableStateOf(false) }
    var leaveTarget by remember { mutableStateOf<RoomId?>(null) }

    val store = remember(session) { ReadStateStore(session) }
    if (selectedId != null && items.none { it.details.roomId == selectedId }) selectedId = null

    // Mark selected room read whenever its preview advances.
    val selectedPreviewTs = items.firstOrNull { it.details.roomId == selectedId }?.preview?.timestamp
    LaunchedEffect(selectedId, selectedPreviewTs) {
        selectedId?.let { store.markRead(it, Clock.System.now().toEpochMilliseconds()) }
    }

    val newcomerState by runtime.onboarding.newcomerState.collectAsState()
    // Cancelling burns the one-time secret: there is no way back short of a reset and a
    // fresh setup, and a sponsor's intro arriving afterwards is refused. Always confirm.
    var confirmCancelOnboarding by remember { mutableStateOf(false) }
    val cancelOnboarding = { confirmCancelOnboarding = true }
    val cancelledMsg = stringResource(Res.string.toast_onboarding_cancelled)
    val roomCopiedMsg = stringResource(Res.string.toast_room_copied)
    val inviteCopiedMsg = stringResource(Res.string.toast_invite_copied)
    val reallyCancelOnboarding = {
        scope.launch {
            runCatching { runtime.onboarding.newcomerCancelOnboarding() }
            session.showToast(cancelledMsg, ToastKind.Info)
        }
    }
    // Staged join invite (new-account / add-device paths): SetupScreen is gone by
    // the time the result arrives. Bytes stay staged while onboarding is active so
    // the banner can reopen the overlay after Done; cleared once it completes.
    val pendingInvite by session.pendingInviteBytes.collectAsState()
    var showInvite by remember { mutableStateOf(false) }
    LaunchedEffect(pendingInvite) {
        if (pendingInvite != null) showInvite = true
    }
    LaunchedEffect(newcomerState) {
        if (newcomerState == OnboardingState.COMPLETE || newcomerState == OnboardingState.IDLE) {
            if (pendingInvite != null) session.clearPendingInvite()
        }
    }

    // Flex shell (web equivalent: `display: flex; height: 100vh`).
    // Rail + room list are fixed widths, chat is `flex: 1` (weight(1f)), the right
    // panel is either docked (Wide) or overlaid (narrower). The outer Box is the
    // positioning context for overlays (right panel, toasts) so they float above
    // the row instead of pushing it — previously ToastHost was a sibling of the
    // Row and competed for space.
    // BoxWithConstraints also gives the live window size to anything inside that
    // needs breakpoints (right-panel dock vs overlay). Note: this does NOT extend
    // into Dialogs — those are separate OS windows. Settings is therefore an
    // in-window overlay (see SettingsModal), not a Dialog.
    BoxWithConstraints(modifier.fillMaxSize().background(colors.bgApp)) {
        // Single breakpoint for the member panel, decided once at the root: the
        // docked and overlay branches used to measure 1150.dp in different scopes
        // (Row child vs root), which disagreed over a range of widths and showed
        // neither. One boolean here keeps them complementary by construction.
        val wide = maxWidth >= 1150.dp
        // Track the breakpoint: crossing it drops the manual toggle back to the
        // size default (hidden when narrow, shown when wide).
        LaunchedEffect(wide) { rightOverride = null }
        val showRight = rightOverride ?: wide
        Column(Modifier.fillMaxSize()) {
            TransportBanner(transportStatus)
            NewcomerStatusBanner(
                state = newcomerState,
                onShowCode = pendingInvite?.let { { showInvite = true } },
                onCancel = { cancelOnboarding() },
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
            // Rail (72, fixed)
            Rail(
                destination = destination,
                onHome = { destination = RailDestination.Home },
                onFriends = { destination = RailDestination.Friends },
                onSponsor = { showSponsor = true },
                onNewRoom = { showCreate = true },
            )
            when (destination) {
                RailDestination.Friends -> StubPage(
                    title = stringResource(Res.string.stub_friends_title),
                    headline = stringResource(Res.string.stub_friends_headline),
                    explanation = stringResource(Res.string.stub_friends_body),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )

                RailDestination.Home -> {
                    // Room list (300, fixed) — flex column: list grows, user panel pinned.
                    Column(Modifier.width(300.dp).fillMaxHeight().background(colors.bgApp)) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            RoomList(
                                items = items,
                                selectedId = selectedId,
                                localId = local?.accountId,
                                availability = availability,
                                onSelect = { selectedId = it },
                                onNewRoom = { showCreate = true },
                                onFind = { showSwitcher = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth().height(1.dp)
                                .background(colors.borderNormal),
                        )
                        UserPanel(local, onSettings = { showSettings = true })
                    }
                    // Chat (`flex: 1`)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val current = selectedId
                        if (current == null) {
                            NoRoomSelected(items.map { it.title }.take(3))
                        } else {
                            val title = items.firstOrNull { it.details.roomId == current }?.title ?: "Room"
                            val detailsState = remember(current) {
                                kotlinx.coroutines.flow.MutableStateFlow(
                                    items.firstOrNull { it.details.roomId == current }?.details,
                                )
                            }
                            LaunchedEffect(current) {
                                runCatching { runtime.rooms.room(current) }.getOrNull()?.let {
                                    detailsState.value = it
                                }
                            }
                            val details by detailsState.collectAsState()
                            ChatView(
                                runtime = runtime,
                                roomId = current,
                                title = title,
                                localId = local?.accountId,
                                names = names,
                                availability = availability,
                                maxBytes = runtime.messaging.maxTextMessageBytes,
                                onRoomRef = { ref -> selectedId = ref },
                                onOpenMembers = { rightOverride = !showRight },
                                onLeave = { leaveTarget = current },
                                onCloseView = { selectedId = null },
                                sendWithEnter = prefs.chat.sendWithEnter,
                                showCharCount = prefs.chat.showCharCount,
                                timestamps24h = prefs.chat.timestamps24h,
                                trailingOverlay = {
                                    // Narrow windows only (!wide; wide docks the panel
                                    // in the row instead): floats over the message list,
                                    // top-end anchored, ending exactly at the input box.
                                    if (!wide && showRight) {
                                        val overlayDetails = details
                                        if (overlayDetails != null) {
                                            val accountMap = remember(accounts) {
                                                accounts.associateBy { it.accountId }
                                            }
                                            Box(
                                                Modifier.align(Alignment.TopEnd).fillMaxHeight()
                                                    .padding(top = 8.dp, end = 8.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(colors.bgApp),
                                            ) {
                                                RightPanel(
                                                    overlayDetails,
                                                    accountMap,
                                                    local?.accountId,
                                                    onClose = { rightOverride = false },
                                                    onCopyRoomId = {
                                                        copyToClipboard(overlayDetails.roomId.value.toString())
                                                        session.showToast(roomCopiedMsg, ToastKind.Success)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                },
                                onMarkRead = { store.markRead(current, Clock.System.now().toEpochMilliseconds()) },
                                persistDraft = { store.saveDraft(current, it) },
                                initialDraft = prefs.drafts[roomKey(current)] ?: "",
                                modifier = Modifier.fillMaxSize(),
                            )
                            @Suppress("UNUSED_EXPRESSION")
                            details
                        }
                    }
                    // Docked right panel (Wide only): [wide] comes from the root
                    // breakpoint above. Narrower windows use the overlay below so
                    // chat is never squeezed to zero.
                    if (showRight && selectedId != null && wide) {
                        val details = items.firstOrNull { it.details.roomId == selectedId }?.details
                        if (details != null) {
                            val accountMap = remember(accounts) { accounts.associateBy { it.accountId } }
                            RightPanel(
                                details,
                                accountMap,
                                local?.accountId,
                                onClose = { rightOverride = false },
                                onCopyRoomId = {
                                    copyToClipboard(details.roomId.value.toString())
                                    session.showToast(roomCopiedMsg, ToastKind.Success)
                                },
                            )
                        }
                    }
                }
            }
            } // Row
        } // Column (banner + shell row)

        // Global ToastHost now lives at App root so reset/start failures are
        // visible in every orchestrator state, not just Running.

        // In-window overlay (see SettingsModal): drawn above the shell. The global
        // ToastHost at App root stays on top so feedback remains visible.
        if (showSettings) {
            SettingsModal(session, runtime, onClose = { showSettings = false })
        }
    }

    if (showCreate) {
        CreateRoomDialog(
            accounts = accounts.filter { it.accountId != local?.accountId },
            localId = local?.accountId,
            onDismiss = { showCreate = false },
            onCreate = { name, members ->
                scope.launch {
                    // Empty stays empty: the backend stores "" as-is (no
                    // auto-generated name) and the list renders the other
                    // participants instead (RoomsController fallback title).
                    when (val r = runtime.rooms.createRoom(name.trim(), members)) {
                        is org.yapyap.orchestrator.runtime.room.CreateRoomResult.Created -> {
                            showCreate = false
                            selectedId = r.roomId
                        }

                        is org.yapyap.orchestrator.runtime.room.CreateRoomResult.Refused ->
                            session.showToast(
                                RefusalText.createRoom(r.reason),
                                org.yapyap.gui.session.ToastKind.Error,
                            )
                    }
                }
            },
        )
    }
    if (showSwitcher) {
        QuickSwitcher(
            items = items,
            onOpen = {
                selectedId = it
                destination = RailDestination.Home
                showSwitcher = false
            },
            onClose = { showSwitcher = false },
        )
    }
    val inviteBytes = pendingInvite
    if (showInvite && inviteBytes != null) {
        PendingInviteOverlay(
            inviteCode = remember(inviteBytes) { Base64Encoder.encode(inviteBytes) },
            newcomerState = newcomerState,
            addressPublished = transportStatus == LocalTransportStatus.Online,
            onCancelOnboarding = { cancelOnboarding() },
            onDismiss = { showInvite = false },
            onCopied = { session.showToast(inviteCopiedMsg, ToastKind.Success) },
        )
    }
    if (showSponsor) {
        SponsorDialog(
            runtime = runtime,
            session = session,
            onDismiss = { showSponsor = false },
        )
    }
    if (confirmCancelOnboarding) {
        org.yapyap.gui.components.ConfirmDialog(
            title = stringResource(Res.string.cancel_join_title),
            body = stringResource(Res.string.cancel_join_body),
            confirmLabel = stringResource(Res.string.cancel_join_confirm),
            onConfirm = {
                confirmCancelOnboarding = false
                reallyCancelOnboarding()
            },
            onCancel = { confirmCancelOnboarding = false },
        )
    }
    leaveTarget?.let { target ->
        val couldntLeave = stringResource(Res.string.toast_leave_failed)
        org.yapyap.gui.components.ConfirmDialog(
            title = stringResource(Res.string.leave_title),
            body = stringResource(Res.string.leave_body),
            confirmLabel = stringResource(Res.string.leave_confirm),
            onConfirm = {
                leaveTarget = null
                scope.launch {
                    val res = runCatching { runtime.rooms.leaveRoom(target) }.getOrElse {
                        session.showToast(
                            it.message ?: couldntLeave,
                            org.yapyap.gui.session.ToastKind.Error,
                        )
                        return@launch
                    }
                    when (res) {
                        is org.yapyap.orchestrator.runtime.room.RoomEventOutcome.Published -> {
                            if (selectedId == target) selectedId = null
                        }

                        is org.yapyap.orchestrator.runtime.room.RoomEventOutcome.Refused ->
                            session.showToast(
                                RefusalText.roomEvent(res.reason),
                                org.yapyap.gui.session.ToastKind.Error,
                            )
                    }
                }
            },
            onCancel = { leaveTarget = null },
        )
    }
}

@Composable
private fun PendingInviteOverlay(
    inviteCode: String,
    newcomerState: OnboardingState,
    addressPublished: Boolean,
    onCancelOnboarding: () -> Unit,
    onDismiss: () -> Unit,
    onCopied: () -> Unit = {},
) {
    val colors = yapColors
    var copied by remember(inviteCode) { mutableStateOf(false) }
    // Explicit buttons only: Done merely hides (the banner reopens), so neither
    // scrim-click nor Esc dismisses.
    ScrimOverlay(onDismiss = null, maxContentWidth = 480.dp) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(YapRadius.md))
                .background(colors.bgSurface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                stringResource(Res.string.invite_title),
                style = yapType.headingXl.copy(color = colors.textStrong),
                modifier = Modifier.fillMaxWidth(),
            )
            BasicText(
                stringResource(Res.string.invite_body),
                style = yapType.bodySm.copy(color = colors.textSubtle),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            // QR slot (M1 follow-up): qrose renders these same bytes here side by
            // side with the text. Placeholder keeps the geometry honest meanwhile.
            Box(
                Modifier.padding(top = 12.dp).size(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SoonChip()
                    BasicText(
                        stringResource(Res.string.invite_qr_soon),
                        style = yapType.bodySm.copy(color = colors.textMuted),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            if (newcomerState == OnboardingState.AWAITING_INTRO) {
                BasicText(
                    stringResource(Res.string.invite_waiting),
                    style = yapType.bodySmMedium.copy(color = colors.info),
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!addressPublished) {
                    BasicText(
                        stringResource(Res.string.invite_not_published),
                        style = yapType.bodyXs.copy(color = colors.textMuted),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else if (newcomerState == OnboardingState.SYNCING) {
                BasicText(
                    stringResource(Res.string.invite_syncing),
                    style = yapType.bodySmMedium.copy(color = colors.positive),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            SelectionContainer(Modifier.padding(top = 12.dp)) {
                BasicText(
                    inviteCode,
                    style = yapType.bodyXs.copy(color = colors.textSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.bgApp, RoundedCornerShape(YapRadius.sm))
                        .padding(12.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                if (newcomerState == OnboardingState.AWAITING_INTRO ||
                    newcomerState == OnboardingState.REQUEST_SENT ||
                    newcomerState == OnboardingState.REQUEST_DELIVERED ||
                    newcomerState == OnboardingState.SYNCING
                ) {
                    YapButton(
                        stringResource(Res.string.invite_cancel_onboarding),
                        onClick = onCancelOnboarding,
                        variant = YapButtonVariant.Danger,
                    )
                }
                Spacer(Modifier.weight(1f))
                YapButton(
                    if (copied) stringResource(Res.string.invite_copied) else stringResource(Res.string.invite_copy),
                    onClick = {
                        copyToClipboard(inviteCode)
                        copied = true
                        onCopied()
                    },
                    variant = YapButtonVariant.Secondary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                YapButton(stringResource(Res.string.common_done), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun Rail(
    destination: RailDestination,
    onHome: () -> Unit,
    onFriends: () -> Unit,
    onSponsor: () -> Unit,
    onNewRoom: () -> Unit,
) {
    val colors = yapColors
    // Fixed-width flex column: icons centred, one spacer (weight 1f) pushes nothing
    // to the bottom. Settings lives only in the user panel (Discord-correct) —
    // the second gear that used to sit here was the "duplicated settings button".
    Column(
        Modifier.width(72.dp).fillMaxHeight().background(colors.bgApp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailItem(FontAwesomeIcons.Solid.Home, destination == RailDestination.Home, onHome)
        RailItem(FontAwesomeIcons.Solid.Users, destination == RailDestination.Friends, onFriends)
        Spacer(Modifier.height(16.dp))
        RailItem(FontAwesomeIcons.Solid.Plus, false, onNewRoom)
        RailItem(FontAwesomeIcons.Solid.PlusCircle, false, onSponsor)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun RailItem(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val colors = yapColors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Flex row: 4 dp pill gutter (transparent when unselected, so icons never shift)
    // + centred 40 dp circle. Previously the pill was overlaid inside the circle's
    // Box, so it overlapped the icon and shifted alignment.
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(4.dp).height(24.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) colors.textStrong else colors.bgApp.copy(alpha = 0f)),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (selected) colors.brand else if (hovered) colors.modHover else colors.bgRaised)
                    .clickable(interactionSource = interaction, indication = null) { onClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(20.dp), colors.textStrong)
            }
        }
    }
}

@Composable
private fun NoRoomSelected(recent: List<String>) {
    val colors = yapColors
    Box(
        Modifier.fillMaxSize().background(colors.bgChat).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("✦", style = yapType.headingXxl.copy(color = colors.brand))
            BasicText(
                stringResource(Res.string.shell_no_room_title),
                style = yapType.headingLg.copy(color = colors.textStrong),
                modifier = Modifier.padding(top = 12.dp),
            )
            recent.forEach {
                BasicText(
                    it,
                    style = yapType.bodySm.copy(color = colors.textMuted),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    AvailabilityLabel.ONLINE
}
