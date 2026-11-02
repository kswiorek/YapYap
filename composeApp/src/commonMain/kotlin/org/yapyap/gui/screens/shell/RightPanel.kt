package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Copy
import compose.icons.fontawesomeicons.solid.Plus
import compose.icons.fontawesomeicons.solid.Times
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.components.Avatar
import org.yapyap.gui.components.StatusDot
import org.yapyap.gui.components.YapButton
import org.yapyap.gui.components.YapButtonVariant
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.identity.AccountView
import org.yapyap.orchestrator.runtime.identity.AvailabilityLabel
import org.yapyap.orchestrator.runtime.room.RoomDetails
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomMemberRole
import org.yapyap.protocol.RoomMemberStatus
import yapyap.composeapp.generated.resources.* // Res + generated string accessors

@Composable
fun RightPanel(
    details: RoomDetails,
    accounts: Map<AccountId, AccountView>,
    localId: AccountId?,
    onClose: () -> Unit,
    onCopyRoomId: () -> Unit = {},
    /** When set (username click in chat), shows this account's profile. */
    focusAccountId: AccountId? = null,
    /** Hoisted: dialogs render at the MainShell window overlay, not in this Row. */
    onAddMembers: () -> Unit,
    onManageMember: (AccountId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    val isGroup = details.members.size > 2 || details.name.isNotBlank()
    // Render policy (docs/room events.md §3): ACTIVE rows render; REMOVED rows
    // are retained by the fold as history and never listed.
    val activeMembers = remember(details) {
        details.members.filter { it.status == RoomMemberStatus.ACTIVE }
    }
    val localMembership = remember(details, localId) {
        details.members.firstOrNull { it.accountId == localId }
    }
    val isLocalAdmin = localMembership?.role == RoomMemberRole.OWNER ||
            localMembership?.role == RoomMemberRole.ADMIN
    val localActive = localMembership?.status == RoomMemberStatus.ACTIVE
    val canManage = isLocalAdmin && localActive

    // Fixed 264 dp pane, flex column: fixed header, weight(1f) list.
    Column(
        modifier.background(colors.bgApp).fillMaxHeight().width(264.dp).padding(12.dp),
    ) {
        val focusView = focusAccountId?.let { accounts[it] }
        val focusRole = focusAccountId?.let { id ->
            details.members.firstOrNull { it.accountId == id }?.role
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BasicText(
                if (focusView != null) focusView.displayName
                else if (isGroup) stringResource(
                    Res.string.room_members_title,
                    activeMembers.size
                ) else stringResource(Res.string.room_profile_title),
                style = yapType.bodyMdMedium.copy(color = colors.textStrong),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // Solo rooms and DMs can grow into groups: the entry point shows
            // whenever the local user may manage, not just in the list view.
            if (canManage) {
                Box(
                    Modifier.clickable { onAddMembers() }.padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        FontAwesomeIcons.Solid.Plus,
                        stringResource(Res.string.room_add_members),
                        Modifier.size(16.dp),
                        colors.iconDefault
                    )
                }
            }
            Box(
                Modifier.clickable { onCopyRoomId() }.padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FontAwesomeIcons.Solid.Copy,
                    stringResource(Res.string.desc_copy_room),
                    Modifier.size(16.dp),
                    colors.iconDefault
                )
            }
            Box(
                Modifier.clickable { onClose() }.padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FontAwesomeIcons.Solid.Times,
                    stringResource(Res.string.desc_close_panel),
                    Modifier.size(16.dp),
                    colors.iconDefault
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (focusView != null) {
                ProfileBody(
                    view = focusView,
                    roomRole = focusRole,
                    onManage = if (canManage && focusAccountId != localId) {
                        { onManageMember(focusAccountId!!) }
                    } else null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (isGroup) {
                MemberList(
                    details = details,
                    accounts = accounts,
                    localId = localId,
                    canManage = canManage,
                    onManage = onManageMember,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Prefer an ACTIVE other member; a removed DM peer falls back
                // to the self profile (just-you state) instead of a stale card.
                val selfMember = details.members.firstOrNull { it.accountId == localId }
                val otherMember = details.members.firstOrNull {
                    it.accountId != localId && it.status == RoomMemberStatus.ACTIVE
                } ?: selfMember ?: details.members.firstOrNull()
                val view = otherMember?.let { accounts[it.accountId] }
                if (view != null && otherMember != null) {
                    ProfileBody(
                        view = view,
                        roomRole = otherMember.role,
                        onManage = if (canManage && otherMember.accountId != localId) {
                            { onManageMember(otherMember.accountId) }
                        } else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else BasicText(
                    stringResource(Res.string.room_unknown_user),
                    style = yapType.bodySm.copy(color = colors.textMuted),
                )
            }
        }
    }
}

@Composable
private fun MemberList(
    details: RoomDetails,
    accounts: Map<AccountId, AccountView>,
    localId: AccountId?,
    canManage: Boolean,
    onManage: (AccountId) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ACTIVE only — REMOVED rows are fold history, never listed.
    val visible = details.members.filter { it.status == RoomMemberStatus.ACTIVE }
    val online = visible.filter {
        accounts[it.accountId]?.availability?.label == AvailabilityLabel.ONLINE
    }
    val offline = visible.filterNot {
        accounts[it.accountId]?.availability?.label == AvailabilityLabel.ONLINE
    }
    LazyColumn(modifier.fillMaxSize()) {
        if (online.isNotEmpty()) {
            item(key = "h-online") {
                SectionHeader(stringResource(Res.string.room_online_count, online.size))
            }
            items(online, key = { "m:${it.accountId.id}" }) { m ->
                MemberRow(
                    view = accounts[m.accountId],
                    id = m.accountId,
                    role = m.role,
                    isLocal = m.accountId == localId,
                    manageable = canManage && m.accountId != localId,
                    onManage = { onManage(m.accountId) },
                )
            }
        }
        if (offline.isNotEmpty()) {
            item(key = "h-offline") {
                SectionHeader(stringResource(Res.string.room_offline_count, offline.size))
            }
            items(offline, key = { "m:${it.accountId.id}" }) { m ->
                MemberRow(
                    view = accounts[m.accountId],
                    id = m.accountId,
                    role = m.role,
                    isLocal = m.accountId == localId,
                    manageable = canManage && m.accountId != localId,
                    onManage = { onManage(m.accountId) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    BasicText(
        text,
        style = yapType.headingXs.copy(color = yapColors.textMuted),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MemberRow(
    view: AccountView?,
    id: AccountId,
    role: RoomMemberRole,
    isLocal: Boolean,
    manageable: Boolean,
    onManage: () -> Unit,
) {
    val colors = yapColors
    val name = view?.displayName ?: stringResource(Res.string.room_unknown_user)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(YapRadius.sm))
            .then(
                if (manageable) {
                    Modifier.clickable { onManage() }
                        // Desktop right-click opens the same manage sheet;
                        // touch/long-press users get it via the left-click path.
                        .pointerInput(onManage) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press &&
                                        event.button == PointerButton.Secondary
                                    ) {
                                        onManage()
                                    }
                                }
                            }
                        }
                } else Modifier
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(id, name, size = 32.dp)
            StatusDot(
                view?.availability?.label?.let { it == AvailabilityLabel.ONLINE },
                Modifier.align(Alignment.BottomEnd).size(10.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                if (isLocal) stringResource(Res.string.member_you_suffix, name) else name,
                style = yapType.bodySmMedium.copy(color = colors.textDefault)
            )
            view?.let {
                BasicText(
                    presenceText(it),
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                )
            }
        }
        RoleChip(role)
    }
}

/** Room-membership badge (OWNER/ADMIN only — plain members get no chip). */
@Composable
private fun RoleChip(role: RoomMemberRole) {
    if (role != RoomMemberRole.OWNER && role != RoomMemberRole.ADMIN) return
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(yapColors.modSubtle)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        BasicText(
            if (role == RoomMemberRole.OWNER) stringResource(Res.string.member_role_owner)
            else stringResource(Res.string.member_role_admin),
            style = yapType.bodyXs.copy(color = yapColors.textMuted),
        )
    }
}

@Composable
fun ProfileBody(
    view: AccountView,
    roomRole: RoomMemberRole?,
    onManage: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    LazyColumn(modifier.fillMaxSize()) {
        item {
            // Banner 105 dp deterministic gradient
            Box(
                Modifier.fillMaxWidth().height(105.dp)
                    .clip(RoundedCornerShape(YapRadius.sm))
                    .background(brushFor(view.accountId.id)),
            )
            Box(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                Box(Modifier.padding(top = 61.dp)) {
                    Avatar(view.accountId, view.displayName, size = 80.dp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    view.displayName,
                    style = yapType.headingLg.copy(color = colors.textStrong),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                // Room membership only — the global network role is not shown here.
                if (roomRole != null) RoleChip(roomRole)
            }
            BasicText(
                presenceText(view),
                style = yapType.bodySm.copy(color = colors.textDefault),
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            )
            // Device inventory lives in Settings → Devices; here just the count,
            // like the members page ("N devices").
            BasicText(
                pluralStringResource(Res.plurals.member_device_count, view.devices.size, view.devices.size),
                style = yapType.bodySm.copy(color = colors.textMuted),
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            )
            // Admins manage a DM peer from the profile — the list view (with the
            // same sheet) only exists for groups.
            if (onManage != null) {
                YapButton(
                    stringResource(Res.string.room_manage_member),
                    onClick = onManage,
                    variant = YapButtonVariant.Secondary,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun presenceText(view: AccountView): String = when (view.availability.label) {
    AvailabilityLabel.ONLINE -> stringResource(Res.string.presence_online)
    AvailabilityLabel.OFFLINE -> stringResource(Res.string.presence_offline)
    AvailabilityLabel.UNKNOWN -> stringResource(Res.string.presence_unknown)
}

private fun brushFor(seed: String): Brush {
    val h = (seed.fold(0) { acc, c -> acc * 31 + c.code } and 0x7FFFFFFF) % 360
    return Brush.linearGradient(
        listOf(Color.hsv(h.toFloat(), 0.45f, 0.45f), Color.hsv(((h + 60) % 360).toFloat(), 0.5f, 0.3f)),
    )
}
