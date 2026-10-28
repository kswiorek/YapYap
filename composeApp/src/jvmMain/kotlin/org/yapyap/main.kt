package org.yapyap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.io.files.Path
import org.yapyap.gui.session.*
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.LoopbackTransport
import java.awt.MouseInfo
import java.awt.Point
import java.io.File

/**
 * Desktop entry with custom 32 dp title bar (10-windows.md §0).
 *
 * Move/resize use ABSOLUTE screen pointer coordinates
 * (`MouseInfo.getPointerInfo().location`) against AWT window bounds — the same
 * technique as JetBrains' own `UndecoratedWindowResizer`. Both sides are
 * physical screen pixels, so there is no DPI/scale factor to get wrong on any
 * OS, display server (X11/Wayland) or monitor.
 */
fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        println(DESKTOP_ARGS_USAGE)
        return
    }
    val cli = try {
        parseDesktopArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        return
    }
    val dataDir: Path = cli.dataDir?.let(::Path) ?: resolveDataDirectory()
    val devMarker = if (cli.loopback != LoopbackTransport.NONE) {
        " — LOCAL" + (cli.keyringNamespace?.let { " $it" } ?: "")
    } else {
        ""
    }
    val prefsFile = File(dataDir.toString(), "ui-prefs.json")
    val session = YapSession(
        dataDirectory = dataDir,
        loopback = cli.loopback,
        keyringNamespace = cli.keyringNamespace,
        notificationSink = platformNotificationSink(),
        readPrefs = { runCatching { if (prefsFile.exists()) prefsFile.readText() else null }.getOrNull() },
        writePrefs = { raw ->
            runCatching {
                prefsFile.parentFile?.mkdirs()
                prefsFile.writeText(raw)
            }
        },
    )
    application {
        val windowState = rememberWindowState(
            position = WindowPosition.PlatformDefault,
            width = 1280.dp,
            height = 800.dp,
        )
        Window(
            onCloseRequest = {
                session.stop()
                exitApplication()
            },
            state = windowState,
            title = "YapYap$devMarker",
            undecorated = true,
            resizable = true,
        ) {
            val colors = yapColors
            // Dark AWT background: no light flash behind Compose content.
            remember(window) {
                window.background = java.awt.Color(0x2C, 0x2D, 0x32)
                true
            }
            val mover = remember(window) { WindowMover(window) }
            val resizer = remember(window) { WindowResizer(window) }
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().background(colors.bgApp)) {
                    TitleBar(
                        mover = mover,
                        devMarker = devMarker,
                        onMinimize = { windowState.isMinimized = true },
                        onToggleMax = {
                            windowState.placement = if (windowState.placement ==
                                WindowPlacement.Maximized
                            ) {
                                WindowPlacement.Floating
                            } else {
                                WindowPlacement.Maximized
                            }
                        },
                        onClose = {
                            session.stop()
                            exitApplication()
                        },
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        App(session)
                    }
                }
                ResizeOverlay(resizer)
            }
        }
    }
}

/** Absolute-pointer window mover. All coordinates are physical screen pixels. */
private class WindowMover(private val window: java.awt.Window) {
    private var startPointer: Point? = null
    private var startWindow: Point? = null

    fun onStart() {
        startPointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        startWindow = runCatching { window.locationOnScreen }.getOrNull()
    }

    fun onMove() {
        val anchor = startPointer ?: return
        val origin = startWindow ?: return
        val now = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return
        window.setLocation(origin.x + now.x - anchor.x, origin.y + now.y - anchor.y)
    }
}

/** Absolute-pointer edge resizer (360×480 minimum per 10-windows.md §0). */
private class WindowResizer(private val window: java.awt.Window) {
    private var startPointer: Point? = null
    private var startBounds: java.awt.Rectangle? = null

    fun onStart() {
        startPointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        startBounds = runCatching { window.bounds }.getOrNull()
    }

    fun onDrag(edges: Set<Edge>) {
        val anchor = startPointer ?: return
        val start = startBounds ?: return
        val now = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return
        var x = start.x
        var y = start.y
        var w = start.width
        var h = start.height
        val dx = now.x - anchor.x
        val dy = now.y - anchor.y
        if (Edge.LEFT in edges) {
            val dw = dx.coerceAtMost(w - MIN_W)
            x += dw
            w -= dw
        }
        if (Edge.RIGHT in edges) w = (w + dx).coerceAtLeast(MIN_W)
        if (Edge.BOTTOM in edges) h = (h + dy).coerceAtLeast(MIN_H)
        window.setBounds(x, y, w, h)
    }

    companion object {
        const val MIN_W = 360
        const val MIN_H = 480
    }
}

private enum class Edge { LEFT, RIGHT, BOTTOM }

@Composable
private fun TitleBar(
    mover: WindowMover,
    devMarker: String,
    onMinimize: () -> Unit,
    onToggleMax: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = yapColors
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(colors.bgApp)
            .pointerInput(mover) {
                detectDragGestures(
                    onDragStart = { mover.onStart() },
                    onDrag = { change, _ ->
                        change.consume()
                        mover.onMove()
                    },
                )
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onDoubleClick = onToggleMax,
                onClick = {},
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "✦ YapYap$devMarker",
            style = yapType.bodySmMedium.copy(color = colors.textSubtle),
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        TitleButton("–", onMinimize)
        TitleButton("▢", onToggleMax)
        TitleButton("✕", onClose, hoverDanger = true)
    }
}

@Composable
private fun TitleButton(label: String, onClick: () -> Unit, hoverDanger: Boolean = false) {
    val colors = yapColors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.width(46.dp).height(32.dp)
            .background(if (hoverDanger) colors.bgApp else colors.bgApp)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = yapType.bodySm.copy(color = colors.textSubtle))
    }
    @Suppress("UNUSED_EXPRESSION")
    hoverDanger
    @Suppress("UNUSED_EXPRESSION")
    hovered
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(
        clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() },
    )

/** 6 px resize borders around the window content. */
@Composable
private fun ResizeOverlay(resizer: WindowResizer) {
    val t = 6.dp
    Box(Modifier.fillMaxSize()) {
        // Bottom edge
        Box(
            Modifier.fillMaxWidth().height(t).align(Alignment.BottomCenter)
                .pointerInput(resizer) {
                    detectDragGestures(
                        onDragStart = { resizer.onStart() },
                        onDrag = { change, _ ->
                            change.consume()
                            resizer.onDrag(setOf(Edge.BOTTOM))
                        },
                    )
                },
        )
        // Right edge
        Box(
            Modifier.width(t).fillMaxHeight().align(Alignment.CenterEnd)
                .pointerInput(resizer) {
                    detectDragGestures(
                        onDragStart = { resizer.onStart() },
                        onDrag = { change, _ ->
                            change.consume()
                            resizer.onDrag(setOf(Edge.RIGHT))
                        },
                    )
                },
        )
        // Left edge
        Box(
            Modifier.width(t).fillMaxHeight().align(Alignment.CenterStart)
                .pointerInput(resizer) {
                    detectDragGestures(
                        onDragStart = { resizer.onStart() },
                        onDrag = { change, _ ->
                            change.consume()
                            resizer.onDrag(setOf(Edge.LEFT))
                        },
                    )
                },
        )
    }
}
