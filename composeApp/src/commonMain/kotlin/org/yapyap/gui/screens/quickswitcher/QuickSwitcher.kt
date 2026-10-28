package org.yapyap.gui.screens.quickswitcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import org.yapyap.gui.components.ScrimOverlay
import org.yapyap.gui.state.RoomListItem
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.protocol.RoomId

@Composable
fun QuickSwitcher(
    items: List<RoomListItem>,
    onOpen: (RoomId) -> Unit,
    onClose: () -> Unit,
) {
    val colors = yapColors
    var query by remember { mutableStateOf("") }
    var index by remember { mutableStateOf(0) }
    val filtered = remember(items, query) {
        if (query.isBlank()) items.take(5)
        else items.filter { it.title.contains(query, ignoreCase = true) }.take(10)
    }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    // In-window overlay (W6): scrim-click/Esc dismiss, search field takes focus.
    ScrimOverlay(onDismiss = onClose, maxContentWidth = 680.dp) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(YapRadius.md))
                .background(colors.bgSurfaceHigh)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(16.dp)
                .onPreviewKeyEvent { ev ->
                    when (ev.key) {
                        Key.DirectionDown -> {
                            index = (index + 1).coerceAtMost(filtered.size - 1); true
                        }

                        Key.DirectionUp -> {
                            index = (index - 1).coerceAtLeast(0); true
                        }

                        Key.Enter -> {
                            filtered.getOrNull(index)?.let { onOpen(it.details.roomId) }; true
                        }

                        Key.Escape -> {
                            onClose(); true
                        }

                        else -> false
                    }
                },
        ) {
            BasicText(
                "Where would you like to go?",
                style = yapType.bodyMd.copy(color = colors.textDefault),
            )
            org.yapyap.gui.components.YapTextField(
                query,
                { query = it; index = 0 },
                placeholder = "Search rooms…",
                modifier = Modifier.padding(top = 8.dp).focusRequester(searchFocus),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 8.dp)) {
                itemsIndexed(filtered, key = { _, it -> it.details.roomId.value.toString() }) { i, item ->
                    BasicText(
                        item.title,
                        style = yapType.bodyMd.copy(
                            color = if (i == index) colors.textStrong else colors.textSubtle,
                        ),
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(YapRadius.sm))
                            .background(if (i == index) colors.modActive else colors.bgApp.copy(alpha = 0f))
                            .clickable { onOpen(item.details.roomId) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}
