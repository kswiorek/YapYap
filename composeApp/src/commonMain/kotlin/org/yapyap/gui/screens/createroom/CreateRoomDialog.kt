package org.yapyap.gui.screens.createroom

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
import compose.icons.fontawesomeicons.solid.Circle
import compose.icons.fontawesomeicons.solid.DotCircle
import compose.icons.fontawesomeicons.solid.Times
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.components.*
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.identity.AccountView
import org.yapyap.protocol.AccountId
import yapyap.composeapp.generated.resources.* // Res + generated string accessors

@Composable
fun CreateRoomDialog(
    accounts: List<AccountView>,
    localId: AccountId? = null,
    onDismiss: () -> Unit,
    onCreate: (name: String, members: Set<AccountId>) -> Unit,
) {
    val colors = yapColors
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<AccountId>()) }
    // Defensive: callers filter out the local account, but never offer yourself
    // here even if the local account isn't known yet at the call site.
    val others = remember(accounts, localId) {
        if (localId == null) accounts else accounts.filter { it.accountId != localId }
    }
    val filtered = remember(others, query) {
        if (query.isBlank()) others
        else others.filter { it.displayName.contains(query, ignoreCase = true) }
    }
    val canCreate = selected.isNotEmpty()
    fun create() {
        if (canCreate) onCreate(name.trim(), selected)
    }
    // In-window overlay (W7): scrim-click/Esc dismiss, window stays resizable.
    // Card: max 480 wide, shrinking with 16 dp margins on narrow windows.
    // Enter creates (universal ScrimOverlay onConfirm).
    ScrimOverlay(
        onDismiss = onDismiss,
        maxContentWidth = 480.dp,
        onConfirm = ::create,
        confirmEnabled = canCreate,
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
                    stringResource(Res.string.create_title),
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
            YapTextField(name, { name = it }, placeholder = stringResource(Res.string.create_name_hint))
            Spacer(Modifier.height(8.dp))
            YapTextField(query, { query = it }, placeholder = stringResource(Res.string.create_search_hint))
            if (filtered.isEmpty()) {
                BasicText(
                    if (others.isEmpty()) stringResource(Res.string.create_empty_roster)
                    else stringResource(Res.string.create_no_match, query),
                    style = yapType.bodySm.copy(color = colors.textMuted),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                )
            }
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
                        if (isSel) {
                            Icon(
                                FontAwesomeIcons.Solid.DotCircle,
                                stringResource(Res.string.desc_selected),
                                Modifier.size(20.dp),
                                colors.brand
                            )
                        } else {
                            Icon(
                                FontAwesomeIcons.Solid.Circle,
                                stringResource(Res.string.desc_not_selected),
                                Modifier.size(20.dp),
                                colors.textSubtle
                            )
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
                    stringResource(Res.string.common_create),
                    onClick = ::create,
                    enabled = canCreate,
                )
            }
        }
    }
}
