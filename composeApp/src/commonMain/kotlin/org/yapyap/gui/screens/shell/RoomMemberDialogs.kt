package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.*
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.components.*
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.identity.AccountView
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomMemberRole
import yapyap.composeapp.generated.resources.*

/**
 * Multi-select add-member picker with a per-member role dropdown.
 * The backend has no add-with-role call: the caller adds first, then grants
 * admin where requested, and reports both outcomes honestly.
 */
@Composable
fun AddMembersDialog(
    candidates: List<AccountView>,
    onDismiss: () -> Unit,
    onConfirm: (Map<AccountId, RoomMemberRole>) -> Unit,
) {
    val colors = yapColors
    var query by remember { mutableStateOf("") }
    // Presence = selected; value = room role assigned on add (default MEMBER).
    var selected by remember { mutableStateOf(mapOf<AccountId, RoomMemberRole>()) }
    var expandedFor by remember { mutableStateOf<AccountId?>(null) }
    val filtered = remember(candidates, query) {
        if (query.isBlank()) candidates
        else candidates.filter { it.displayName.contains(query, ignoreCase = true) }
    }

    fun toggle(id: AccountId) {
        val cur = selected.toMutableMap()
        if (cur.remove(id) == null) cur[id] = RoomMemberRole.MEMBER
        else if (expandedFor == id) expandedFor = null
        selected = cur
    }

    fun confirm() {
        if (selected.isNotEmpty()) onConfirm(selected)
    }

    @Composable
    fun roleLabel(role: RoomMemberRole): String = when (role) {
        RoomMemberRole.MEMBER -> stringResource(Res.string.room_role_member)
        RoomMemberRole.ADMIN -> stringResource(Res.string.room_role_admin)
        RoomMemberRole.OWNER -> stringResource(Res.string.member_role_owner)
    }
    ScrimOverlay(
        onDismiss = onDismiss,
        maxContentWidth = 480.dp,
        onConfirm = ::confirm,
        confirmEnabled = selected.isNotEmpty(),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(YapRadius.md))
                .background(colors.bgSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    stringResource(Res.string.room_add_title),
                    style = yapType.headingXl.copy(color = colors.textStrong),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.clickable { onDismiss() }.padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        FontAwesomeIcons.Solid.Times,
                        stringResource(Res.string.desc_close),
                        Modifier.size(16.dp),
                        colors.textMuted
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            YapTextField(query, { query = it }, placeholder = stringResource(Res.string.room_add_search))
            if (filtered.isEmpty()) {
                BasicText(
                    stringResource(Res.string.room_add_empty),
                    style = yapType.bodySm.copy(color = colors.textMuted),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().height(240.dp).padding(top = 8.dp)) {
                items(filtered, key = { it.accountId.id }) { a ->
                    val role = selected[a.accountId]
                    val isSel = role != null
                    val expanded = expandedFor == a.accountId && isSel
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(YapRadius.sm))
                            .background(if (isSel) colors.modActive else colors.bgApp.copy(alpha = 0f)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { toggle(a.accountId) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(a.accountId, a.displayName, size = 32.dp)
                            Spacer(Modifier.width(8.dp))
                            BasicText(
                                a.displayName,
                                style = yapType.bodySmMedium.copy(color = colors.textDefault),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (isSel) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(YapRadius.sm))
                                        .background(colors.modSubtle)
                                        .clickable {
                                            expandedFor =
                                                if (expanded) null else a.accountId
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        BasicText(
                                            roleLabel(role!!),
                                            style = yapType.bodyXsMedium.copy(color = colors.textDefault),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            if (expanded) FontAwesomeIcons.Solid.ChevronUp
                                            else FontAwesomeIcons.Solid.ChevronDown,
                                            stringResource(Res.string.desc_role),
                                            Modifier.size(12.dp),
                                            colors.textMuted
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(
                                if (isSel) FontAwesomeIcons.Solid.DotCircle else FontAwesomeIcons.Solid.Circle,
                                if (isSel) stringResource(Res.string.desc_selected)
                                else stringResource(Res.string.desc_not_selected),
                                Modifier.size(20.dp),
                                if (isSel) colors.brand else colors.textSubtle
                            )
                        }
                        if (expanded) {
                            // Only MEMBER/ADMIN are grantable; OWNER transfers
                            // solely via leave-with-successor.
                            listOf(RoomMemberRole.MEMBER, RoomMemberRole.ADMIN).forEach { option ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            val cur = selected.toMutableMap()
                                            cur[a.accountId] = option
                                            selected = cur
                                            expandedFor = null
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicText(
                                        roleLabel(option),
                                        style = yapType.bodySm.copy(
                                            color = if (option == role) colors.textStrong
                                            else colors.textSubtle,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (option == role) {
                                        Icon(
                                            FontAwesomeIcons.Solid.DotCircle,
                                            stringResource(Res.string.desc_selected),
                                            Modifier.size(16.dp),
                                            colors.brand
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                YapButton(
                    stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    variant = YapButtonVariant.Secondary
                )
                Spacer(Modifier.width(8.dp))
                YapButton(
                    stringResource(Res.string.room_add_confirm),
                    onClick = ::confirm,
                    enabled = selected.isNotEmpty(),
                )
            }
        }
    }
}

/**
 * Per-member admin actions for a non-self member. Owner targets and
 * non-ACTIVE targets render a note only — never actions.
 */
@Composable
fun MemberActionDialog(
    displayName: String,
    isOwnerTarget: Boolean,
    isActiveTarget: Boolean,
    showGrant: Boolean,
    showRevoke: Boolean,
    showRemove: Boolean,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = yapColors
    ScrimOverlay(onDismiss = onDismiss, maxContentWidth = 400.dp) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(YapRadius.md))
                .background(colors.bgSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    stringResource(Res.string.room_manage_title, displayName),
                    style = yapType.headingLg.copy(color = colors.textStrong),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Box(
                    Modifier.clickable { onDismiss() }.padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        FontAwesomeIcons.Solid.Times,
                        stringResource(Res.string.desc_close),
                        Modifier.size(16.dp),
                        colors.textMuted
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (isOwnerTarget) {
                BasicText(
                    stringResource(Res.string.room_owner_note),
                    style = yapType.bodySm.copy(color = colors.textMuted),
                )
            } else if (!isActiveTarget) {
                BasicText(
                    stringResource(Res.string.room_no_actions),
                    style = yapType.bodySm.copy(color = colors.textMuted),
                )
            } else {
                if (showGrant) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(YapRadius.sm))
                            .clickable { onGrant() }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            stringResource(Res.string.room_make_admin),
                            style = yapType.bodySmMedium.copy(color = colors.textDefault),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (showRevoke) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(YapRadius.sm))
                            .clickable { onRevoke() }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            stringResource(Res.string.room_revoke_admin),
                            style = yapType.bodySmMedium.copy(color = colors.textDefault),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (showRemove) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(YapRadius.sm))
                            .clickable { onRemove() }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            stringResource(Res.string.room_remove_member),
                            style = yapType.bodySmMedium.copy(color = colors.danger),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Owner-aware leave: non-owners get a plain confirm; owners must pick an
 * ACTIVE successor (backend refuses owner leave without one). No eligible
 * successor means no confirm — the dialog says so honestly.
 */
@Composable
fun LeaveRoomDialog(
    isOwner: Boolean,
    successors: List<AccountView>,
    onConfirm: (AccountId?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = yapColors
    var picked by remember(successors) { mutableStateOf<AccountId?>(null) }
    val canConfirm = if (!isOwner) true else picked != null
    fun confirm() {
        if (canConfirm) onConfirm(if (isOwner) picked else null)
    }
    ScrimOverlay(
        onDismiss = onDismiss,
        maxContentWidth = 480.dp,
        onConfirm = ::confirm,
        confirmEnabled = canConfirm,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(YapRadius.md))
                .background(colors.bgSurface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(20.dp),
        ) {
            BasicText(
                if (isOwner) stringResource(Res.string.leave_successor_title)
                else stringResource(Res.string.leave_title),
                style = yapType.headingLg.copy(color = colors.textStrong),
            )
            BasicText(
                if (isOwner) stringResource(Res.string.leave_successor_body)
                else stringResource(Res.string.leave_body),
                style = yapType.bodySm.copy(color = colors.textSubtle),
                modifier = Modifier.padding(top = 8.dp),
            )
            if (isOwner) {
                if (successors.isEmpty()) {
                    BasicText(
                        stringResource(Res.string.leave_no_successor),
                        style = yapType.bodySm.copy(color = colors.danger),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 12.dp)) {
                        items(successors, key = { it.accountId.id }) { a ->
                            val isSel = a.accountId == picked
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(YapRadius.sm))
                                    .background(if (isSel) colors.modActive else colors.bgApp.copy(alpha = 0f))
                                    .clickable { picked = a.accountId }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(a.accountId, a.displayName, size = 32.dp)
                                Spacer(Modifier.width(8.dp))
                                BasicText(
                                    a.displayName,
                                    style = yapType.bodySmMedium.copy(color = colors.textDefault),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Icon(
                                    if (isSel) FontAwesomeIcons.Solid.DotCircle
                                    else FontAwesomeIcons.Solid.Circle,
                                    if (isSel) stringResource(Res.string.desc_selected)
                                    else stringResource(Res.string.desc_not_selected),
                                    Modifier.size(20.dp),
                                    if (isSel) colors.brand else colors.textSubtle
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                YapButton(
                    stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    variant = YapButtonVariant.Secondary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                YapButton(
                    if (isOwner) stringResource(Res.string.leave_transfer_confirm)
                    else stringResource(Res.string.leave_confirm),
                    onClick = ::confirm,
                    enabled = canConfirm,
                    variant = YapButtonVariant.Danger,
                )
            }
        }
    }
}
