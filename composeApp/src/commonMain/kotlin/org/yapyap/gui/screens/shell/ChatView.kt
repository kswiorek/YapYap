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
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Bars
import compose.icons.fontawesomeicons.solid.Plus
import compose.icons.fontawesomeicons.solid.SignOutAlt
import compose.icons.fontawesomeicons.solid.Times
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.components.Avatar
import org.yapyap.gui.components.StatusDot
import org.yapyap.gui.components.isConfirmKeyPress
import org.yapyap.gui.markdown.DiscordMarkdownTransformer
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
import yapyap.composeapp.generated.resources.*
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
    onCloseView: () -> Unit,
    sendWithEnter: Boolean = true,
    showCharCount: Boolean = true,
    timestamps24h: Boolean = true,
    trailingOverlay: @Composable BoxScope.() -> Unit = {},
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
            Box(
                Modifier.clickable { onOpenMembers() }.padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FontAwesomeIcons.Solid.Bars,
                    stringResource(Res.string.desc_members),
                    Modifier.size(18.dp),
                    yapColors.iconDefault
                )
            }
            Box(
                Modifier.clickable { onLeave() }.padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FontAwesomeIcons.Solid.SignOutAlt,
                    stringResource(Res.string.desc_leave_room),
                    Modifier.size(18.dp),
                    yapColors.danger
                )
            }
            Box(
                Modifier.clickable { onCloseView() }.padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FontAwesomeIcons.Solid.Times,
                    stringResource(Res.string.desc_close_view),
                    Modifier.size(18.dp),
                    yapColors.iconDefault
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderNormal))

        // Messages
        // The narrow-window member overlay renders here (trailingOverlay), not at
        // the shell root: this Box ends exactly where the input box begins, so the
        // panel's bottom edge always clears the composer with no measurement.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty() && ui.pending.isEmpty()) {
                EmptyRoom(title)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                ) {
                    items(rows, key = { rowKey(it) }, contentType = { "group" }) { row ->
                        MessageGroup(
                            row = row,
                            names = names,
                            availability = availability,
                            controller = controller,
                            timestamps24h = timestamps24h,
                            onRoomRef = onRoomRef,
                        )
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
                        stringResource(Res.string.chat_jump_to_present),
                        style = yapType.bodyXs.copy(color = colors.textStrong),
                    )
                }
            }
            trailingOverlay()
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
                    stringResource(Res.string.chat_dismiss),
                    style = yapType.bodyXs.copy(color = colors.textSubtle),
                    modifier = Modifier.clickable { controller.dismissSendError() }.padding(4.dp),
                )
            }
        }

        // Typing indicator: sits directly above the composer (near the input),
        // not above the message list. Fixed 22 dp slot so the composer never jumps.
        Box(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 16.dp)) {
            val typing = ui.typingNames
            if (typing.isNotEmpty()) {
                BasicText(
                    when (typing.size) {
                        1 -> stringResource(Res.string.chat_typing_one, typing[0])
                        2 -> stringResource(Res.string.chat_typing_two, typing[0], typing[1])
                        else -> stringResource(Res.string.chat_typing_many)
                    },
                    style = yapType.bodyXs.copy(color = colors.textMuted),
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
            Box(
                Modifier.padding(end = 8.dp).clickable {
                    scope.launch { /* M3: file picker stub */ }
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FontAwesomeIcons.Solid.Plus,
                    stringResource(Res.string.desc_attach),
                    Modifier.size(18.dp),
                    colors.textMuted
                )
            }
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
                        // Key-down only: without the type guard both press and release
                        // fire send(), posting the message twice. NumpadEnter is a
                        // separate key constant — handle it exactly like Enter.
                        if (ev.type != KeyEventType.KeyDown || !isConfirmKeyPress(ev.key)) return@onPreviewKeyEvent false
                        val send = if (sendWithEnter) {
                            !ev.isShiftPressed
                        } else {
                            ev.isCtrlPressed || ev.isMetaPressed
                        }
                        if (send) {
                            controller.send(composer, maxBytes)
                            if (composer.trim().isNotEmpty()) composer = ""
                            true
                        } else {
                            false
                        }
                    },
                textStyle = yapType.bodyMd.copy(color = colors.textDefault),
                cursorBrush = SolidColor(colors.textDefault),
                decorationBox = { inner ->
                    // Same overlay rule as YapTextField: placeholder and input share
                    // one Box frame instead of stacking vertically.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (composer.isEmpty()) {
                            BasicText(
                                stringResource(Res.string.chat_composer_hint, title),
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
            if (showCharCount && used > maxBytes * 0.9) {
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

// ---- Grouping (Discord-style): messages join under one author header with a
// full stamp ("Today at 21:07"); a >5-minute gap or a day change re-shows the
// avatar with a fresh stamp. No separate date-divider rows. ----

internal sealed interface ChatRow {
    data class Group(val author: AccountId, val items: List<MessageDisplayItem>) : ChatRow
}

/**
 * LazyColumn keys must be unique. A gap shares its orphan message's id (it stands for
 * that message's missing predecessor), so gap rows need their own prefix: with `grp:`
 * for both, an orphan starting a new group crashed the list on its duplicate key.
 */
internal fun rowKey(row: ChatRow): String {
    val first = (row as ChatRow.Group).items.first()
    return if (first is MessageDisplayItem.Gap) "gap:${first.messageId}" else "grp:${first.messageId}"
}

internal fun groupItems(items: List<MessageDisplayItem>): List<ChatRow.Group> {
    val out = mutableListOf<ChatRow.Group>()
    var lastDayEpoch: Int? = null
    var currentAuthor: AccountId? = null
    var currentStart = 0L
    var current: MutableList<MessageDisplayItem>? = null
    fun flush() {
        current?.let { out += ChatRow.Group(currentAuthor!!, it.toList()) }
        current = null
        currentAuthor = null
    }
    val tz = TimeZone.currentSystemDefault()
    for (item in items) {
        val dayEpoch = item.timestamp.toLocalDateTime(tz).date.toEpochDays().toInt()
        if (dayEpoch != lastDayEpoch) {
            flush()
            lastDayEpoch = dayEpoch
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

/** 0 = today, 1 = yesterday, 2+ = older. Pure (testable) day bucket. */
internal fun dayOffset(t: Instant, now: Instant = Clock.System.now()): Int {
    val tz = TimeZone.currentSystemDefault()
    return ((now.toLocalDateTime(tz).date.toEpochDays() - t.toLocalDateTime(tz).date.toEpochDays())).toInt()
}

/** Clock time, 24h ("21:07") or 12h ("9:07 PM"). Markers keep AM/PM localizable. */
internal fun formatTime(t: Instant, use24h: Boolean, am: String = "AM", pm: String = "PM"): String {
    val local = t.toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    if (use24h) return "${two(local.hour)}:${two(local.minute)}"
    val h12 = when (local.hour % 12) {
        0 -> 12
        else -> local.hour % 12
    }
    return "$h12:${two(local.minute)} ${if (local.hour < 12) am else pm}"
}

internal fun monthRes(m: kotlinx.datetime.Month): StringResource = when (m) {
    kotlinx.datetime.Month.JANUARY -> Res.string.month_january
    kotlinx.datetime.Month.FEBRUARY -> Res.string.month_february
    kotlinx.datetime.Month.MARCH -> Res.string.month_march
    kotlinx.datetime.Month.APRIL -> Res.string.month_april
    kotlinx.datetime.Month.MAY -> Res.string.month_may
    kotlinx.datetime.Month.JUNE -> Res.string.month_june
    kotlinx.datetime.Month.JULY -> Res.string.month_july
    kotlinx.datetime.Month.AUGUST -> Res.string.month_august
    kotlinx.datetime.Month.SEPTEMBER -> Res.string.month_september
    kotlinx.datetime.Month.OCTOBER -> Res.string.month_october
    kotlinx.datetime.Month.NOVEMBER -> Res.string.month_november
    kotlinx.datetime.Month.DECEMBER -> Res.string.month_december
}

@Composable
private fun MessageGroup(
    row: ChatRow.Group,
    names: Map<AccountId, String>,
    availability: Map<AccountId, AvailabilityLabel>,
    controller: ChatController,
    timestamps24h: Boolean,
    onRoomRef: (RoomId) -> Unit,
) {
    val colors = yapColors
    val first = row.items.first()
    if (first is MessageDisplayItem.Gap) {
        GapDivider()
        return
    }
    val name = names[first.accountId] ?: stringResource(Res.string.room_unknown_user)
    val stamp = groupStampText(first.timestamp, timestamps24h)
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
                    stamp,
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

/** Full Discord-style group stamp ("Today at 21:07"), localized via resources. */
@Composable
private fun groupStampText(t: Instant, use24h: Boolean): String {
    val am = stringResource(Res.string.time_am)
    val pm = stringResource(Res.string.time_pm)
    val time = remember(t, use24h, am, pm) { formatTime(t, use24h, am, pm) }
    return when (dayOffset(t)) {
        0 -> stringResource(Res.string.chat_today_at, time)
        1 -> stringResource(Res.string.chat_yesterday_at, time)
        else -> {
            val local = t.toLocalDateTime(TimeZone.currentSystemDefault())
            val date = stringResource(
                Res.string.chat_date,
                local.day,
                stringResource(monthRes(local.month)),
                local.year,
            )
            stringResource(Res.string.chat_date_at, date, time)
        }
    }
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
                        stringResource(Res.string.chat_not_sent, failed),
                        style = yapType.bodyXs.copy(color = colors.danger),
                        modifier = Modifier.weight(1f),
                    )
                    BasicText(
                        stringResource(Res.string.chat_retry),
                        style = yapType.bodyXs.copy(color = colors.textLink),
                        modifier = Modifier.clickable { onRetry() }.padding(4.dp),
                    )
                }
            } else {
                BasicText(
                    stringResource(Res.string.chat_sending),
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
            stringResource(Res.string.chat_empty_body),
            style = yapType.bodySm.copy(color = colors.textMuted),
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(2f))
    }
}
