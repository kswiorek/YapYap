package org.yapyap.gui.screens.createroom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.yapyap.gui.components.*
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.identity.AccountView
import org.yapyap.protocol.AccountId

@Composable
fun CreateRoomDialog(
    accounts: List<AccountView>,
    onDismiss: () -> Unit,
    onCreate: (name: String, members: Set<AccountId>) -> Unit,
) {
    val colors = yapColors
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<AccountId>()) }
    val filtered = remember(accounts, query) {
        if (query.isBlank()) accounts
        else accounts.filter { it.displayName.contains(query, ignoreCase = true) }
    }
    // In-window overlay (W7): scrim-click/Esc dismiss, window stays resizable.
    // Card: max 480 wide, shrinking with 16 dp margins on narrow windows.
    ScrimOverlay(onDismiss = onDismiss, maxContentWidth = 480.dp) {
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
                    "Create a room",
                    style = yapType.headingXl.copy(color = colors.textStrong),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    "✕",
                    style = yapType.headingMd.copy(color = colors.textMuted),
                    modifier = Modifier.clickable { onDismiss() }.padding(4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            YapTextField(name, { name = it }, placeholder = "Room name (optional)")
            Spacer(Modifier.height(8.dp))
            YapTextField(query, { query = it }, placeholder = "Search people…")
            LazyColumn(Modifier.fillMaxWidth().height(240.dp).padding(top = 8.dp)) {
                items(filtered, key = { it.accountId.id }) { a ->
                    val isSel = a.accountId in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(YapRadius.sm))
                            .background(if (isSel) colors.modActive else colors.bgApp.copy(alpha = 0f))
                            .clickable {
                                selected = if (isSel) selected - a.accountId else selected + a.accountId
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(a.accountId, a.displayName, size = 32.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                a.displayName,
                                style = yapType.bodySmMedium.copy(color = colors.textDefault),
                            )
                            BasicText(
                                a.role.name.lowercase(),
                                style = yapType.bodyXs.copy(color = colors.textMuted),
                            )
                        }
                        BasicText(
                            if (isSel) "◉" else "○",
                            style = yapType.bodyLg.copy(color = colors.textSubtle),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                YapButton("Cancel", onClick = onDismiss, variant = YapButtonVariant.Secondary)
                Spacer(Modifier.width(8.dp))
                YapButton(
                    "Create",
                    onClick = { onCreate(name.trim(), selected) },
                    enabled = selected.isNotEmpty(),
                )
            }
        }
    }
}
