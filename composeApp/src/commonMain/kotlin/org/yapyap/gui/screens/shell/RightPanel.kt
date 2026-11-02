package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Copy
import compose.icons.fontawesomeicons.solid.Times
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.components.Avatar
import org.yapyap.gui.components.StatusDot
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.identity.AccountView
import org.yapyap.orchestrator.runtime.identity.AvailabilityLabel
import org.yapyap.orchestrator.runtime.room.RoomDetails
import org.yapyap.protocol.AccountId
import yapyap.composeapp.generated.resources.* // Res + generated string accessors

@Composable
fun RightPanel(
    details: RoomDetails,
    accounts: Map<AccountId, AccountView>,
    localId: AccountId?,
    onClose: () -> Unit,
    onCopyRoomId: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    val isGroup = details.members.size > 2 || details.name.isNotBlank()
    // Fixed 264 dp pane, flex column: fixed header, weight(1f) list.
    // Previously the LazyColumns had no weight, so they measured unbounded
    // inside the Column and content overlapped / failed to scroll.
    Column(
        modifier.background(colors.bgApp).fillMaxHeight().width(264.dp).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BasicText(
                if (isGroup) stringResource(
                    Res.string.room_members_title,
                    details.members.size
                ) else stringResource(Res.string.room_profile_title),
                style = yapType.bodyMdMedium.copy(color = colors.textStrong),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
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
            if (isGroup) {
                MemberList(details, accounts, localId, modifier = Modifier.fillMaxSize())
            } else {
                val other = details.members.firstOrNull { it.accountId != localId }?.accountId
                    ?: details.members.firstOrNull()?.accountId
                val view = other?.let { accounts[it] }
                if (view != null) ProfileBody(view, modifier = Modifier.fillMaxSize())
                else BasicText(
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
    modifier: Modifier = Modifier,
) {
    val online = details.members.filter {
        accounts[it.accountId]?.availability?.label == AvailabilityLabel.ONLINE
    }
    val offline = details.members.filterNot {
        accounts[it.accountId]?.availability?.label == AvailabilityLabel.ONLINE
    }
    LazyColumn(modifier.fillMaxSize()) {
        if (online.isNotEmpty()) {
            item(key = "h-online") {
                SectionHeader(stringResource(Res.string.room_online_count, online.size))
            }
            items(online, key = { "m:${it.accountId.id}" }) { m ->
                MemberRow(accounts[m.accountId], m.accountId, m.role.name, m.accountId == localId)
            }
        }
        if (offline.isNotEmpty()) {
            item(key = "h-offline") {
                SectionHeader(stringResource(Res.string.room_offline_count, offline.size))
            }
            items(offline, key = { "m:${it.accountId.id}" }) { m ->
                MemberRow(accounts[m.accountId], m.accountId, m.role.name, m.accountId == localId)
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

@Composable
private fun MemberRow(view: AccountView?, id: AccountId, role: String, isLocal: Boolean) {
    val colors = yapColors
    val name = view?.displayName ?: stringResource(Res.string.room_unknown_user)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(YapRadius.sm))
            .clickable { }
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
        if (role == "OWNER" || role == "ADMIN") {
            Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(colors.modSubtle)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                BasicText(
                    if (role == "OWNER") stringResource(Res.string.member_role_owner)
                    else stringResource(Res.string.member_role_admin),
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                )
            }
        }
    }
}

@Composable
fun ProfileBody(view: AccountView, modifier: Modifier = Modifier) {
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
            BasicText(
                view.displayName,
                style = yapType.headingLg.copy(color = colors.textStrong),
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
            BasicText(
                presenceText(view) + " · ${view.role.name.lowercase()}",
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
