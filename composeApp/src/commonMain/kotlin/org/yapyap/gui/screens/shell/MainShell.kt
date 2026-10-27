package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.yapyap.gui.components.StubPage
import org.yapyap.gui.components.ToastHost
import org.yapyap.gui.copy.RefusalText
import org.yapyap.gui.screens.createroom.CreateRoomDialog
import org.yapyap.gui.screens.quickswitcher.QuickSwitcher
import org.yapyap.gui.screens.settings.SettingsModal
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.state.ReadStateStore
import org.yapyap.gui.state.RoomsController
import org.yapyap.gui.state.roomKey
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.OrchestratorRuntime
import org.yapyap.orchestrator.runtime.identity.AvailabilityLabel
import org.yapyap.protocol.RoomId
import kotlin.time.Clock

private enum class RailDestination { Home, Friends }

@Composable
fun MainShell(session: YapSession, runtime: OrchestratorRuntime, modifier: Modifier = Modifier) {
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
    var showRight by remember { mutableStateOf(prefs.layout.showMemberListByDefault) }
    var showCreate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    var leaveTarget by remember { mutableStateOf<RoomId?>(null) }

    val store = remember(session) { ReadStateStore(session) }
    if (selectedId != null && items.none { it.details.roomId == selectedId }) selectedId = null

    // Mark selected room read whenever its preview advances.
    val selectedPreviewTs = items.firstOrNull { it.details.roomId == selectedId }?.preview?.timestamp
    LaunchedEffect(selectedId, selectedPreviewTs) {
        selectedId?.let { store.markRead(it, Clock.System.now().toEpochMilliseconds()) }
    }

    val toast by session.toast.collectAsState()
    val stubToast = { session.showToast(RefusalText.STUB_TOAST) }

    Row(modifier.fillMaxSize().background(colors.bgApp)) {
        // Rail (72)
        Rail(
            destination = destination,
            onHome = { destination = RailDestination.Home },
            onFriends = { destination = RailDestination.Friends },
            onAddServer = stubToast,
            onNewRoom = { showCreate = true },
            onSettings = { showSettings = true },
        )
        when (destination) {
            RailDestination.Friends -> StubPage(
                title = "Friends",
                headline = "Friends are coming soon",
                explanation = "You'll be able to add people you know and see who's around.",
                modifier = Modifier.weight(1f),
            )

            RailDestination.Home -> {
                // Room list (300)
                Box(Modifier.width(300.dp).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            RoomList(
                                items = items,
                                selectedId = selectedId,
                                localId = local?.accountId,
                                availability = availability,
                                onSelect = { selectedId = it },
                                onNewRoom = { showCreate = true },
                                onFind = { showSwitcher = true },
                            )
                        }
                        UserPanel(local, onSettings = { showSettings = true })
                    }
                }
                // Chat
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
                            onOpenMembers = { showRight = !showRight },
                            onLeave = { leaveTarget = current },
                            onMarkRead = { store.markRead(current, Clock.System.now().toEpochMilliseconds()) },
                            persistDraft = { store.saveDraft(current, it) },
                            initialDraft = prefs.drafts[roomKey(current)] ?: "",
                        )
                        @Suppress("UNUSED_EXPRESSION")
                        details
                    }
                }
                // Right panel (overlay/docked)
                if (showRight && selectedId != null) {
                    val details = items.firstOrNull { it.details.roomId == selectedId }?.details
                    if (details != null) {
                        val accountMap = remember(accounts) { accounts.associateBy { it.accountId } }
                        RightPanel(details, accountMap, local?.accountId, onClose = { showRight = false })
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateRoomDialog(
            accounts = accounts.filter { it.accountId != local?.accountId },
            onDismiss = { showCreate = false },
            onCreate = { name, members ->
                scope.launch {
                    when (val r = runtime.rooms.createRoom(name.ifBlank { null }, members)) {
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
    if (showSettings) {
        SettingsModal(session, runtime, onClose = { showSettings = false })
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
    leaveTarget?.let { target ->
        org.yapyap.gui.components.ConfirmDialog(
            title = "Leave this room?",
            body = "You will stop receiving messages here. You can be re-added by a member.",
            confirmLabel = "Leave",
            onConfirm = {
                leaveTarget = null
                scope.launch {
                    val res = runCatching { runtime.rooms.leaveRoom(target) }.getOrElse {
                        session.showToast(
                            it.message ?: "Couldn't leave.",
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
    ToastHost(toast, onDismiss = { session.clearToast() })
}

@Composable
private fun Rail(
    destination: RailDestination,
    onHome: () -> Unit,
    onFriends: () -> Unit,
    onAddServer: () -> Unit,
    onNewRoom: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = yapColors
    Column(
        Modifier.width(72.dp).fillMaxHeight().background(colors.bgApp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailItem("✦", destination == RailDestination.Home, onHome)
        RailItem("☺", destination == RailDestination.Friends, onFriends)
        Spacer(Modifier.height(16.dp))
        RailItem("+", false, onNewRoom)
        RailItem("⊕", false, onAddServer)
        Spacer(Modifier.weight(1f))
        RailItem("⚙", false, onSettings)
    }
}

@Composable
private fun RailItem(icon: String, selected: Boolean, onClick: () -> Unit) {
    val colors = yapColors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (selected) colors.brand else if (hovered) colors.modHover else colors.bgRaised)
                .clickable(interactionSource = interaction, indication = null) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(icon, style = yapType.headingMd.copy(color = colors.textStrong))
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterStart).width(4.dp).height(24.dp)
                    .clip(RoundedCornerShape(2.dp)).background(colors.textStrong),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
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
                "Pick up where you left off",
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
