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
import org.jetbrains.compose.resources.stringResource
import org.yapyap.gui.components.*
import org.yapyap.gui.session.ToastKind
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.state.SetupController
import org.yapyap.gui.state.SetupUiState
import org.yapyap.gui.theme.YapRadius
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType
import org.yapyap.orchestrator.SetupResult
import yapyap.composeapp.generated.resources.* // Res + generated string accessors
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private enum class Choice { Genesis, NewAccount, AddDevice, Recover }

@Composable
fun SetupScreen(session: YapSession) {
    val colors = yapColors
    val controller = remember(session) { SetupController(session.scope, session) }
    val uiState by controller.state.collectAsState()
    var choice by remember { mutableStateOf(Choice.Genesis) }

    CenterColumn {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            BasicText("✦  YapYap", style = yapType.headingXxl.copy(color = colors.textStrong))
            BasicText(
                stringResource(Res.string.setup_tagline),
                style = yapType.bodySm.copy(color = colors.textMuted),
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
            ChoiceRow(
                stringResource(Res.string.setup_genesis_title),
                stringResource(Res.string.setup_genesis_desc),
                choice == Choice.Genesis
            ) {
                choice = Choice.Genesis
            }
            ChoiceRow(
                stringResource(Res.string.setup_new_account_title),
                stringResource(Res.string.setup_new_account_desc),
                choice == Choice.NewAccount
            ) {
                choice = Choice.NewAccount
            }
            ChoiceRow(
                stringResource(Res.string.setup_add_device_title),
                stringResource(Res.string.setup_add_device_desc),
                choice == Choice.AddDevice
            ) {
                choice = Choice.AddDevice
            }
            ChoiceRow(
                stringResource(Res.string.setup_recover_title),
                stringResource(Res.string.setup_recover_desc),
                choice == Choice.Recover
            ) {
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
                        stringResource(Res.string.common_continue),
                        onClick = { controller.addDevice() },
                        modifier = Modifier.fillMaxWidth()
                            .onEnterConfirm(enabled = uiState != SetupUiState.Working) { controller.addDevice() },
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
                is SetupUiState.Invite -> InviteResult(result = s.result, session = session)
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
    fun go() {
        if (valid && uiState != SetupUiState.Working) onGo(name.trim())
    }
    Column(Modifier.fillMaxWidth().onEnterConfirm(enabled = valid) { go() }) {
        YapTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(Res.string.setup_name_hint),
            isError = name.isNotEmpty() && !valid,
        )
        if (name.isNotEmpty() && !valid) InlineError(stringResource(Res.string.setup_name_error))
        Spacer(Modifier.height(12.dp))
        YapButton(
            stringResource(Res.string.common_continue),
            onClick = ::go,
            modifier = Modifier.fillMaxWidth(),
            enabled = valid && uiState != SetupUiState.Working,
        )
        if (uiState is SetupUiState.Error) InlineError(uiState.message)
    }
}

@Composable
private fun RecoverForm(uiState: SetupUiState, onGo: (String, String, String, Int) -> Unit) {
    var key by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var onion by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("80") }
    val valid = key.isNotBlank() && device.isNotBlank() && onion.isNotBlank() &&
            uiState != SetupUiState.Working

    fun go() {
        if (valid) onGo(key, device, onion, port.toIntOrNull() ?: 80)
    }
    Column(Modifier.fillMaxWidth().onEnterConfirm(enabled = valid) { go() }) {
        YapTextField(key, { key = it }, placeholder = stringResource(Res.string.setup_key_hint))
        Spacer(Modifier.height(8.dp))
        YapTextField(device, { device = it }, placeholder = stringResource(Res.string.setup_device_hint))
        Spacer(Modifier.height(8.dp))
        YapTextField(onion, { onion = it }, placeholder = stringResource(Res.string.setup_onion_hint))
        Spacer(Modifier.height(8.dp))
        YapTextField(
            port,
            { port = it.filter { c -> c.isDigit() }.take(5) },
            placeholder = stringResource(Res.string.setup_port_hint)
        )
        Spacer(Modifier.height(12.dp))
        YapButton(
            stringResource(Res.string.setup_recover_button),
            onClick = ::go,
            modifier = Modifier.fillMaxWidth(),
            enabled = valid,
        )
        if (uiState is SetupUiState.Error) InlineError(uiState.message)
    }
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
            stringResource(Res.string.setup_working, elapsed),
            style = yapType.bodySm.copy(color = colors.textMuted),
        )
        if (elapsed > 20) {
            BasicText(
                stringResource(Res.string.setup_working_tor),
                style = yapType.bodyXs.copy(color = colors.textMuted),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun InviteResult(result: SetupResult, session: YapSession) {
    val colors = yapColors
    var copied by remember { mutableStateOf(false) }
    val copiedMsg = stringResource(Res.string.toast_invite_copied)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        result.inviteBytes?.let { bytes ->
            BasicText(
                stringResource(Res.string.setup_invite_label),
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
                    if (copied) stringResource(Res.string.invite_copied) else stringResource(Res.string.invite_copy),
                    onClick = {
                        copyToClipboard(bytes.encodeBase64Stable())
                        copied = true
                        session.showToast(copiedMsg, ToastKind.Success)
                    },
                    variant = YapButtonVariant.Secondary,
                )
            }
        }
        result.recoveryKey?.let { key ->
            var ack by remember { mutableStateOf(false) }
            Spacer(Modifier.height(12.dp))
            BasicText(
                stringResource(Res.string.setup_recovery_label),
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
                    if (ack) stringResource(Res.string.setup_stored_done) else stringResource(Res.string.setup_stored),
                    onClick = { ack = !ack },
                    variant = YapButtonVariant.Secondary,
                )
            }
        }
    }
}

expect fun copyToClipboard(text: String)

private fun ByteArray.encodeBase64Stable(): String = Base64Encoder.encode(this)
