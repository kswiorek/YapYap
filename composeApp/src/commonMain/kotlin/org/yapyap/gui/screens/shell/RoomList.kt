package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.yapyap.gui.components.Avatar
import org.yapyap.gui.components.StatusDot
import org.yapyap.gui.markdown.DiscordMarkdownTransformer
import org.yapyap.gui.state.RoomListItem
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.identity.AvailabilityLabel
import org.yapyap.orchestrator.runtime.message.MessageDisplayItem
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId

@Composable
fun RoomList(
    items: List<RoomListItem>,
    selectedId: RoomId?,
    localId: AccountId?,
    availability: Map<AccountId, AvailabilityLabel>,
    onSelect: (RoomId) -> Unit,
    onNewRoom: () -> Unit,
    onFind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    Column(
        modifier.background(colors.bgApp).fillMaxHeight().padding(horizontal = 8.dp),
    ) {
        // Search entry (this is the search entry → quick switcher).
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(YapRadius.sm))
                .background(colors.bgApp.copy(alpha = 0.4f))
                .clickable { onFind() }
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            BasicText(
                "Find or start a conversation",
                style = yapType.bodySm.copy(color = colors.textMuted),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "DIRECT MESSAGES",
                style = yapType.headingXs.copy(color = colors.textMuted),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                "+",
                style = yapType.headingMd.copy(color = colors.textMuted),
                modifier = Modifier.clickable { onNewRoom() }.padding(4.dp),
            )
        }
        if (items.isEmpty()) {
            BasicText(
                "No conversations yet",
                style = yapType.headingSm.copy(color = colors.textSubtle),
                modifier = Modifier.padding(16.dp),
            )
            org.yapyap.gui.components.YapButton(
                "Start a conversation",
                onClick = onNewRoom,
                variant = org.yapyap.gui.components.YapButtonVariant.Secondary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(items, key = { it.details.roomId.value.toString() }) { item ->
                    RoomRow(
                        item = item,
                        selected = item.details.roomId == selectedId,
                        localId = localId,
                        availability = availability,
                        onClick = { onSelect(item.details.roomId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomRow(
    item: RoomListItem,
    selected: Boolean,
    localId: AccountId?,
    availability: Map<AccountId, AvailabilityLabel>,
    onClick: () -> Unit,
) {
    val colors = yapColors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> colors.modActive
        hovered -> colors.modHover
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val isGroup = item.details.members.size > 2 || item.details.name.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YapRadius.sm))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Unread dot
        Box(Modifier.width(8.dp), contentAlignment = Alignment.Center) {
            if (item.unread && !item.muted) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colors.textStrong))
            }
        }
        // Avatar
        val otherId = item.details.members.firstOrNull { it.accountId != localId }?.accountId
            ?: item.details.members.firstOrNull()?.accountId
        if (isGroup) {
            GroupAvatar(item.title)
        } else if (otherId != null) {
            Box {
                Avatar(otherId, item.title, size = 32.dp)
                val online = availability[otherId]?.let { it == AvailabilityLabel.ONLINE }
                StatusDot(online, Modifier.align(Alignment.BottomEnd).size(12.dp))
            }
        } else {
            GroupAvatar(item.title)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                item.title,
                style = (if (item.unread) yapType.bodySmMedium else yapType.bodySmMedium.copy()).copy(
                    color = when {
                        item.muted -> colors.textMuted
                        item.unread || selected -> colors.textStrong
                        else -> colors.textSubtle
                    },
                ),
                maxLines = 1,
            )
            val previewText = when (val p = item.preview?.item) {
                is MessageDisplayItem.Text -> DiscordMarkdownTransformer.stripToPlain(p.text)
                is MessageDisplayItem.File -> "sent a file: ${p.fileName}"
                is MessageDisplayItem.Gap -> "…"
                null -> if (isGroup) "${item.details.members.size} members" else ""
            }
            val typingShown = false // typing line rendered by chat header area instead
            if (!typingShown) {
                BasicText(
                    previewText.take(80),
                    style = yapType.bodyXs.copy(
                        color = if (item.unread) colors.textDefault else colors.textMuted,
                    ),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (item.mentionDot) {
            Box(
                Modifier.clip(CircleShape).background(colors.danger).padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                BasicText("!", style = yapType.bodyXs.copy(color = colors.textStrong))
            }
        }
    }
}

@Composable
private fun GroupAvatar(name: String) {
    val first = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(yapColors.bgAccent),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(first, style = yapType.bodySmMedium.copy(color = yapColors.textStrong))
    }
}
