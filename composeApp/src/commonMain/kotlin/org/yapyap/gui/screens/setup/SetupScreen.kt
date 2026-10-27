package org.yapyap.gui.screens.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.yapyap.gui.components.*
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.state.SetupController
import org.yapyap.gui.state.SetupUiState
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.SetupResult
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private enum class Choice { Genesis, NewAccount, AddDevice, Recover }

@Composable
fun SetupScreen(session: YapSession) {
    val colors = yapColors
    val scope = rememberCoroutineScope()
    val controller = remember(session) { SetupController(scope, session) }
    val uiState by controller.state.collectAsState()
    var choice by remember { mutableStateOf(Choice.Genesis) }

    CenterColumn {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            BasicText("✦  YapYap", style = yapType.headingXxl.copy(color = colors.textStrong))
            BasicText(
                "A private network for people you actually know.",
                style = yapType.bodySm.copy(color = colors.textMuted),
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
            ChoiceRow("Create a new network", "You'll be the owner", choice == Choice.Genesis) {
                choice = Choice.Genesis
            }
            ChoiceRow("Join with a new account", "You were invited", choice == Choice.NewAccount) {
                choice = Choice.NewAccount
            }
            ChoiceRow("Add this device to my account", "Second device, same account", choice == Choice.AddDevice) {
                choice = Choice.AddDevice
            }
            ChoiceRow("Restore from a recovery key", "Disaster recovery", choice == Choice.Recover) {
                choice = Choice.Recover
            }
            Spacer(Modifier.height(16.dp))
            when (choice) {
                Choice.Genesis, Choice.NewAccount -> NameForm(
                    uiState = uiState,
                    onGo = { name ->
                        if (choice == Choice.Genesis) controller.genesis(name)
                        else controller.newAccount(name)
                    },
                )

                Choice.AddDevice -> {
                    YapButton(
                        "Continue",
                        onClick = { controller.addDevice() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState != SetupUiState.Working,
                    )
                    if (uiState is SetupUiState.Error) {
                        InlineError((uiState as SetupUiState.Error).message)
                    }
                }

                Choice.Recover -> RecoverForm(uiState) { key, device, onion, port ->
                    controller.recover(key, device, onion, port)
                }
            }
            when (val s = uiState) {
                is SetupUiState.Invite -> InviteResult(result = s.result)
                is SetupUiState.Working -> WorkingIndicator()
                else -> Unit
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val colors = yapColors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(YapRadius.sm))
            .background(if (selected) colors.modActive else colors.modMuted)
            .then(
                if (selected) Modifier.border(2.dp, colors.brand, RoundedCornerShape(YapRadius.sm))
                else Modifier,
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            BasicText(title, style = yapType.bodyMdMedium.copy(color = colors.textDefault))
            BasicText(desc, style = yapType.bodySm.copy(color = colors.textMuted))
        }
    }
}

@Composable
private fun NameForm(uiState: SetupUiState, onGo: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val valid = name.trim().length in 2..32
    YapTextField(
        value = name,
        onValueChange = { name = it },
        placeholder = "Display name (2–32 chars)",
        isError = name.isNotEmpty() && !valid,
    )
    if (name.isNotEmpty() && !valid) InlineError("Display name must be 2–32 characters.")
    Spacer(Modifier.height(12.dp))
    YapButton(
        "Continue",
        onClick = { onGo(name.trim()) },
        modifier = Modifier.fillMaxWidth(),
        enabled = valid && uiState != SetupUiState.Working,
    )
    if (uiState is SetupUiState.Error) InlineError(uiState.message)
}

@Composable
private fun RecoverForm(uiState: SetupUiState, onGo: (String, String, String, Int) -> Unit) {
    var key by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var onion by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("80") }
    YapTextField(key, { key = it }, placeholder = "Recovery key")
    Spacer(Modifier.height(8.dp))
    YapTextField(device, { device = it }, placeholder = "Bootstrap device ID")
    Spacer(Modifier.height(8.dp))
    YapTextField(onion, { onion = it }, placeholder = "Bootstrap onion address")
    Spacer(Modifier.height(8.dp))
    YapTextField(port, { port = it.filter { c -> c.isDigit() }.take(5) }, placeholder = "Port (80)")
    Spacer(Modifier.height(12.dp))
    YapButton(
        "Recover",
        onClick = { onGo(key, device, onion, port.toIntOrNull() ?: 80) },
        modifier = Modifier.fillMaxWidth(),
        enabled = key.isNotBlank() && device.isNotBlank() && onion.isNotBlank() &&
                uiState != SetupUiState.Working,
    )
    if (uiState is SetupUiState.Error) InlineError(uiState.message)
}

@Composable
private fun WorkingIndicator() {
    val colors = yapColors
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        startedAt = Clock.System.now().toEpochMilliseconds()
        while (true) {
            delay(1.seconds)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    val elapsed = startedAt?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: 0
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        BasicText(
            "Working… ${elapsed}s",
            style = yapType.bodySm.copy(color = colors.textMuted),
        )
        if (elapsed > 20) {
            BasicText(
                "First run bootstraps Tor (downloads consensus) — this can take several minutes. " +
                        "Your progress is safe; leave the window open.",
                style = yapType.bodyXs.copy(color = colors.textMuted),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun InviteResult(result: SetupResult) {
    val colors = yapColors
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        result.inviteBytes?.let { bytes ->
            BasicText(
                "Invite code (sponsor scans or pastes this):",
                style = yapType.bodySmMedium.copy(color = colors.textDefault)
            )
            SelectionContainer(Modifier.padding(top = 8.dp)) {
                BasicText(
                    bytes.encodeBase64Stable(),
                    style = yapType.bodyXs.copy(color = colors.textSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.bgApp, RoundedCornerShape(YapRadius.sm))
                        .padding(12.dp),
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YapButton(
                    "Copy invite",
                    onClick = { copyToClipboard(bytes.encodeBase64Stable()) },
                    variant = YapButtonVariant.Secondary,
                )
            }
        }
        result.recoveryKey?.let { key ->
            var ack by remember { mutableStateOf(false) }
            Spacer(Modifier.height(12.dp))
            BasicText(
                "Save your recovery key — shown once:",
                style = yapType.bodySmMedium.copy(color = colors.warning),
            )
            SelectionContainer(Modifier.padding(top = 8.dp)) {
                BasicText(
                    key,
                    style = yapType.bodySm.copy(color = colors.textDefault),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.bgApp, RoundedCornerShape(YapRadius.sm))
                        .padding(12.dp),
                )
            }
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YapButton(
                    if (ack) "✓ Stored" else "I stored it",
                    onClick = { ack = !ack },
                    variant = YapButtonVariant.Secondary,
                )
            }
        }
    }
}

expect fun copyToClipboard(text: String)

private fun ByteArray.encodeBase64Stable(): String = Base64Encoder.encode(this)

object Base64Encoder {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            out.append(ALPHABET[(b0 shr 2) and 0x3F])
            out.append(ALPHABET[((b0 shl 4) or (b1 shr 4)) and 0x3F])
            out.append(if (i + 1 < bytes.size) ALPHABET[((b1 shl 2) or (b2 shr 6)) and 0x3F] else '=')
            out.append(if (i + 2 < bytes.size) ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }
}
