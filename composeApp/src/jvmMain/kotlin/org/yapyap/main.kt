package org.yapyap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import org.yapyap.gui.components.YapMark
import org.yapyap.gui.session.DESKTOP_ARGS_USAGE
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.session.parseDesktopArgs
import org.yapyap.gui.session.platformNotificationSink
import org.yapyap.gui.session.resolveDataDirectory
import org.yapyap.gui.theme.YapTheme
import org.yapyap.gui.theme.resolveDarkTheme
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.wiring.LoopbackTransport
import java.awt.MouseInfo
import java.awt.Point
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Desktop entry with custom 32 dp title bar (10-windows.md §0).
 *
 * Move/resize use ABSOLUTE screen pointer coordinates
 * (`MouseInfo.getPointerInfo().location`) against AWT window bounds — the same
 * technique as JetBrains' own `UndecoratedWindowResizer`. Both sides are
 * physical screen pixels, so there is no DPI/scale factor to get wrong on any
 * OS, display server (X11/Wayland) or monitor.
 *
 * `--native-titlebar` skips the custom bar and uses the OS frame (native
 * caption buttons). Firefox-style "custom bar, native buttons" is not something
 * Compose Multiplatform can do: an undecorated window gets no OS buttons, and a
 * decorated one draws the OS title bar above the content — there is no API to
 * embed native caption buttons inside Compose content. Per-OS native code
 * (Windows DWM caption customization, macOS full-size content view, GTK
 * headerbar) could do it later; until then this flag is the fallback when the
 * custom buttons misbehave on a given window manager.
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
                // No window handle here (outside the content scope): minimize
                // for instant feedback; the background teardown exits after.
                windowState.isMinimized = true
                beginShutdown(session, ::exitApplication)
            },
            state = windowState,
            title = "YapYap$devMarker",
            undecorated = !cli.nativeTitlebar,
            resizable = true,
        ) {
            // Window chrome follows the same theme pref as App content (it lives
            // outside App's YapTheme, so it gets its own wrapper here).
            val prefs by session.prefsController.prefs.collectAsState()
            YapTheme(darkTheme = resolveDarkTheme(prefs.appearance.theme)) {
                val colors = yapColors
                // AWT background tracks the theme: no light flash behind Compose
                // content in either palette.
                remember(window, colors.bgApp) {
                    window.background = java.awt.Color(
                        (colors.bgApp.red * 255).toInt(),
                        (colors.bgApp.green * 255).toInt(),
                        (colors.bgApp.blue * 255).toInt(),
                    )
                    true
                }
                // Brand icon instead of the generic Java one (bundled raster; the
                // vector originals live in composeApp/icons, drawables in CMP res).
            remember(window) {
                runCatching {
                    YapSession::class.java.getResourceAsStream("/app-icon.png")?.use {
                        javax.imageio.ImageIO.read(it)
                    }?.let { window.iconImage = it }
                }
                true
            }
            if (cli.nativeTitlebar) {
                // OS frame owns min/max/close: no custom bar, no edge overlay.
                Box(Modifier.fillMaxSize().background(colors.bgApp)) {
                    App(session)
                }
            } else {
                val mover = remember(window) { WindowMover(window) }
                val resizer = remember(window) { WindowResizer(window) }
                val isMaximized = windowState.placement == WindowPlacement.Maximized
                // Same UI zoom as App content (WithUiZoom is a sibling wrapper here,
                // so the bar and the content each scale exactly once).
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().background(colors.bgApp)) {
                        WithUiZoom(prefs) {
                            TitleBar(
                                mover = mover,
                                devMarker = devMarker,
                                isMaximized = isMaximized,
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
                                    beginShutdown(window, session, ::exitApplication)
                                },
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            App(session)
                        }
                    }
                    WithUiZoom(prefs) {
                        ResizeOverlay(resizer)
                    }
                }
            }
            } // YapTheme wrapper for the window chrome (see above)
        }
    }
}

/**
 * Runs process teardown off the UI thread: hides the window immediately so a
 * slow stop (dead peers, daemon shutdown) never freezes it, then exits when
 * teardown completes or the cap lapses. Guarded: double-close runs teardown once.
 */
private val shutdownStarted = AtomicBoolean(false)

private fun beginShutdown(session: org.yapyap.gui.session.YapSession, exit: () -> Unit) {
    if (!shutdownStarted.compareAndSet(false, true)) return
    thread(isDaemon = true, name = "yapyap-shutdown") {
        runBlocking {
            withTimeoutOrNull(20_000) { session.awaitStop() }
        }
        exit()
    }
}

private fun beginShutdown(window: java.awt.Window, session: org.yapyap.gui.session.YapSession, exit: () -> Unit) {
    if (!shutdownStarted.compareAndSet(false, true)) return
    window.isVisible = false
    thread(isDaemon = true, name = "yapyap-shutdown") {
        runBlocking {
            withTimeoutOrNull(20_000) { session.awaitStop() }
        }
        exit()
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
    isMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMax: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = yapColors
    val isMac = captionPlatform == CaptionPlatform.Mac
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
        if (isMac) {
            // Traffic lights sit on the left, like every native macOS window.
            TrafficLights(onClose = onClose, onMinimize = onMinimize, onZoom = onToggleMax)
        }
        YapMark(size = 18.dp, modifier = Modifier.padding(start = 12.dp))
        BasicText(
            "YapYap$devMarker",
            style = yapType.bodySmMedium.copy(color = colors.textSubtle),
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        if (!isMac) {
            TitleButton(CaptionKind.Minimize, isMaximized, onMinimize)
            TitleButton(CaptionKind.MaximizeRestore, isMaximized, onToggleMax)
            TitleButton(CaptionKind.Close, isMaximized, onClose, hoverDanger = true)
        }
    }
}

@Composable
private fun TitleButton(
    kind: CaptionKind,
    isMaximized: Boolean,
    onClick: () -> Unit,
    hoverDanger: Boolean = false,
) {
    val colors = yapColors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isWin = captionPlatform == CaptionPlatform.Windows
    // Windows keeps the 46-wide full-flood button (Win11 style); other desks get
    // slightly narrower buttons with a circular hover disc (Breeze/Adwaita style).
    // The shape is a platform-family constant, not per-OS artwork: there is no
    // reliable runtime lookup for control geometry (icon themes carry glyphs,
    // not hover shapes; GTK CSS is parseable in theory but brittle in practice
    // — theme resolution, @imports, variables — so Firefox/Chrome hardcode this
    // exact distinction too).
    val hoverBg = when {
        hovered && hoverDanger -> colors.danger
        hovered -> colors.modHover
        else -> Color.Transparent
    }
    Box(
        Modifier.width(if (isWin) 46.dp else 32.dp).height(32.dp)
            .background(if (isWin) (if (hovered) hoverBg else colors.bgApp) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (isWin) {
            CaptionGlyph(
                kind = kind,
                isMaximized = isMaximized,
                tint = if (hovered) colors.textStrong else colors.textSubtle,
            )
        } else {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(hoverBg),
                contentAlignment = Alignment.Center,
            ) {
                CaptionGlyph(
                    kind = kind,
                    isMaximized = isMaximized,
                    tint = if (hovered) colors.textStrong else colors.textSubtle,
                )
            }
        }
    }
}

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
