package org.yapyap.gui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.yapyap.gui.components.Avatar
import org.yapyap.gui.components.StatusDot
import org.yapyap.gui.markdown.DiscordMarkdownTransformer
import org.yapyap.gui.screens.setup.copyToClipboard
import org.yapyap.gui.state.ChatController
import org.yapyap.gui.state.PendingState
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.runtime.OrchestratorRuntime
import org.yapyap.orchestrator.runtime.identity.AvailabilityLabel
import org.yapyap.orchestrator.runtime.message.MessageDisplayItem
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import kotlin.time.Clock
import kotlin.time.Instant

private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

@Composable
fun ChatView(
    runtime: OrchestratorRuntime,
    roomId: RoomId,
    title: String,
    localId: AccountId?,
    names: Map<AccountId, String>,
    availability: Map<AccountId, AvailabilityLabel>,
    maxBytes: Int,
    onRoomRef: (RoomId) -> Unit,
    onOpenMembers: () -> Unit,
    onLeave: () -> Unit,
    onMarkRead: () -> Unit,
    persistDraft: (String) -> Unit,
    initialDraft: String,
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    val scope = rememberCoroutineScope()
    val controller = remember(roomId) {
        ChatController(scope, runtime, roomId, localId) { names[it] }
    }
    DisposableEffect(roomId) {
        controller.start()
        onDispose { controller.dispose() }
    }
    val ui by controller.state.collectAsState()

    var composer by remember(roomId) { mutableStateOf(initialDraft) }
    val listState = rememberLazyListState()
    var pinnedToBottom by remember(roomId) { mutableStateOf(true) }

    // Auto-scroll on new messages when pinned; mark read.
    LaunchedEffect(ui.items.size, ui.pending.size) {
        if (pinnedToBottom && (ui.items.isNotEmpty() || ui.pending.isNotEmpty())) {
            runCatching { listState.scrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1)) }
        }
        onMarkRead()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.totalItemsCount }
            .collectLatest { (first, total) ->
                pinnedToBottom = first + listState.layoutInfo.visibleItemsInfo.size >= total - 1
                if (first < 3) controller.loadOlder()
            }
    }
    // Persist draft on change (debounced by prefs controller already).
    LaunchedEffect(composer) {
        persistDraft(composer)
    }

    val rows = remember(ui.items) { groupItems(ui.items) }

    Column(modifier.fillMaxSize().background(colors.bgChat)) {
        // Header (49 dp, fixed)
        Row(
            Modifier.fillMaxWidth().height(49.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                title,
                style = yapType.bodyMdMedium.copy(color = colors.textStrong),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            HeaderButton("☰", "Members") { onOpenMembers() }
            HeaderButton("⎘", "Copy room ID") { copyToClipboard(roomId.value.toString()) }
            HeaderButton("✕", "Leave") { onLeave() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderNormal))

        // Typing indicator (24 dp slot)
        Box(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 16.dp)) {
            if (ui.typingNames.isNotEmpty()) {
                BasicText(
                    typingText(ui.typingNames),
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                )
            }
        }

        // Messages
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty() && ui.pending.isEmpty()) {
                EmptyRoom(title)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                ) {
                    items(rows, key = { rowKey(it) }, contentType = { rowType(it) }) { row ->
                        when (row) {
                            is ChatRow.Date -> DateDivider(row.label)
                            is ChatRow.Group -> MessageGroup(
                                row = row,
                                names = names,
                                availability = availability,
                                controller = controller,
                                onRoomRef = onRoomRef,
                            )
                        }
                    }
                    items(ui.pending, key = { "pending:${it.localKey}" }) { p ->
                        PendingRow(
                            text = p.text,
                            failed = (p.state as? PendingState.Failed)?.reason,
                            onRetry = { controller.retryPending(p.localKey, maxBytes) },
                        )
                    }
                }
            }
            if (!pinnedToBottom) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.brand)
                        .clickable {
                            pinnedToBottom = true
                            scope.launch {
                                runCatching {
                                    listState.scrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1))
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    BasicText(
                        "Jump to present",
                        style = yapType.bodyXs.copy(color = colors.textStrong),
                    )
                }
            }
        }

        ui.openError?.let { err ->
            BasicText(
                err,
                style = yapType.bodyXs.copy(color = colors.danger),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        ui.sendError?.let { err ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    err,
                    style = yapType.bodyXs.copy(color = colors.danger),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    "Dismiss",
                    style = yapType.bodyXs.copy(color = colors.textSubtle),
                    modifier = Modifier.clickable { controller.dismissSendError() }.padding(4.dp),
                )
            }
        }

        // Composer
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp)
                .clip(RoundedCornerShape(YapRadius.sm))
                .background(colors.bgSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicText(
                "+",
                style = yapType.headingLg.copy(color = colors.textMuted),
                modifier = Modifier.padding(end = 8.dp).clickable {
                    scope.launch { /* M3: file picker stub */ }
                },
            )
            BasicTextField(
                value = composer,
                onValueChange = {
                    val bytes = it.encodeToByteArray().size
                    if (bytes <= maxBytes + 512) {
                        composer = it
                        controller.onComposerTyped()
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 24.dp, max = 160.dp)
                    .onPreviewKeyEvent { ev ->
                        if (ev.key == Key.Enter && !ev.isShiftPressed) {
                            controller.send(composer, maxBytes)
                            if (composer.trim().isNotEmpty()) composer = ""
                            true
                        } else false
                    },
                textStyle = yapType.bodyMd.copy(color = colors.textDefault),
                cursorBrush = SolidColor(colors.textDefault),
                decorationBox = { inner ->
                    // Same overlay rule as YapTextField: placeholder and input share
                    // one Box frame instead of stacking vertically.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (composer.isEmpty()) {
                            BasicText(
                                "Message $title",
                                style = yapType.bodyMd.copy(color = colors.textPlaceholder),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            val used = composer.encodeToByteArray().size
            if (used > maxBytes * 0.9) {
                BasicText(
                    "$used / $maxBytes",
                    style = yapType.bodyXs.copy(
                        color = if (used > maxBytes) colors.danger else colors.textMuted,
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private fun typingText(names: List<String>): String = when (names.size) {
    1 -> "${names[0]} is typing…"
    2 -> "${names[0]} and ${names[1]} are typing…"
    else -> "Several people are typing…"
}

// ---- Grouping ----

private sealed interface ChatRow {
    data class Date(val label: String) : ChatRow
    data class Group(val author: AccountId, val items: List<MessageDisplayItem>) : ChatRow
}

private fun rowKey(row: ChatRow): String = when (row) {
    is ChatRow.Date -> "date:${row.label}"
    is ChatRow.Group -> "grp:${row.items.first().messageId}"
}

private fun rowType(row: ChatRow): String = when (row) {
    is ChatRow.Date -> "date"
    is ChatRow.Group -> "group"
}

private fun groupItems(items: List<MessageDisplayItem>): List<ChatRow> {
    val out = mutableListOf<ChatRow>()
    var lastDay: String? = null
    var currentAuthor: AccountId? = null
    var currentStart = 0L
    var current: MutableList<MessageDisplayItem>? = null
    fun flush() {
        current?.let { out += ChatRow.Group(currentAuthor!!, it.toList()) }
        current = null
        currentAuthor = null
    }
    for (item in items) {
        val day = dayLabel(item.timestamp)
        if (day != lastDay) {
            flush()
            out += ChatRow.Date(day)
            lastDay = day
        }
        val gap = item.timestamp.toEpochMilliseconds() - currentStart
        if (item is MessageDisplayItem.Gap) {
            flush()
            out += ChatRow.Group(item.accountId, listOf(item))
            currentAuthor = null
            continue
        }
        if (current == null || item.accountId != currentAuthor || gap > GROUP_WINDOW_MS) {
            flush()
            current = mutableListOf()
            currentAuthor = item.accountId
            currentStart = item.timestamp.toEpochMilliseconds()
        } else {
            currentStart = item.timestamp.toEpochMilliseconds()
        }
        current!!.add(item)
    }
    flush()
    return out
}

private fun dayLabel(t: Instant): String {
    val tz = TimeZone.currentSystemDefault()
    val local = t.toLocalDateTime(tz).date
    val today = Clock.System.now().toLocalDateTime(tz).date
    return when ((today.toEpochDays() - local.toEpochDays()).toInt()) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> "${local.day} ${local.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${local.year}"
    }
}

@Composable
private fun DateDivider(label: String) {
    val colors = yapColors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.borderNormal))
        BasicText(
            label,
            style = yapType.bodyXs.copy(color = colors.textMuted),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(colors.borderNormal))
    }
}

@Composable
private fun MessageGroup(
    row: ChatRow.Group,
    names: Map<AccountId, String>,
    availability: Map<AccountId, AvailabilityLabel>,
    controller: ChatController,
    onRoomRef: (RoomId) -> Unit,
) {
    val colors = yapColors
    val first = row.items.first()
    if (first is MessageDisplayItem.Gap) {
        GapDivider()
        return
    }
    val name = names[first.accountId] ?: "Unknown user"
    val mentionedMe = row.items.any {
        it is MessageDisplayItem.Text && controller.mentionsMe(it.text)
    }
    val bg = if (mentionedMe) Color(0x14F8A300) else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(bg).padding(vertical = 8.dp),
    ) {
        Box(Modifier.width(56.dp), contentAlignment = Alignment.TopCenter) {
            val online = availability[first.accountId]?.let { it == AvailabilityLabel.ONLINE }
            Box {
                Avatar(first.accountId, name, size = 40.dp)
                StatusDot(online, Modifier.align(Alignment.BottomEnd).size(12.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                BasicText(
                    name,
                    style = yapType.bodyMdMedium.copy(
                        color = if (names[first.accountId] == null) colors.textMuted else colors.textStrong,
                    ),
                    modifier = Modifier.weight(1f).clickable { }.padding(end = 8.dp),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                BasicText(
                    timeLabel(first.timestamp),
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                    maxLines = 1,
                )
            }
            row.items.forEach { item ->
                if (item is MessageDisplayItem.Text) {
                    MessageBody(item, names, onRoomRef, Modifier.padding(top = 2.dp, end = 24.dp))
                } else {
                    MessageBody(item, names, onRoomRef, Modifier.padding(top = 2.dp, end = 24.dp))
                }
            }
        }
    }
}

private fun timeLabel(t: Instant): String {
    val local = t.toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${two(local.hour)}:${two(local.minute)}"
}

@Composable
private fun PendingRow(text: String, failed: String?, onRetry: () -> Unit) {
    val colors = yapColors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(Modifier.width(56.dp))
        Column(Modifier.weight(1f).background(colors.bgApp.copy(alpha = 0.4f)).padding(8.dp)) {
            BasicText(
                DiscordMarkdownTransformer.stripToPlain(text).take(500),
                style = yapType.bodyMd.copy(color = colors.textSubtle),
            )
            if (failed != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    BasicText(
                        "Not sent — $failed ",
                        style = yapType.bodyXs.copy(color = colors.danger),
                        modifier = Modifier.weight(1f),
                    )
                    BasicText(
                        "Retry",
                        style = yapType.bodyXs.copy(color = colors.textLink),
                        modifier = Modifier.clickable { onRetry() }.padding(4.dp),
                    )
                }
            } else {
                BasicText(
                    "Sending…",
                    style = yapType.bodyXs.copy(color = colors.textMuted),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyRoom(title: String) {
    val colors = yapColors
    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Spacer(Modifier.weight(1f))
        BasicText(title, style = yapType.headingXxl.copy(color = colors.textStrong))
        BasicText(
            "This is the beginning of your conversation.",
            style = yapType.bodySm.copy(color = colors.textMuted),
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(2f))
    }
}

@Composable
private fun HeaderButton(label: String, tooltip: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = yapType.bodyLg.copy(color = yapColors.iconDefault),
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 8.dp, vertical = 4.dp),
    )
    @Suppress("UNUSED_EXPRESSION")
    tooltip
}
