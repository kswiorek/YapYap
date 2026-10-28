package org.yapyap.gui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.yapyap.gui.copy.RefusalText
import org.yapyap.gui.session.YapSession
import org.yapyap.orchestrator.BootstrapEndpoint
import org.yapyap.orchestrator.SetupIntent
import org.yapyap.orchestrator.SetupResult
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import kotlin.coroutines.cancellation.CancellationException

sealed interface SetupUiState {
    data object Idle : SetupUiState
    data object Working : SetupUiState
    data class Invite(val result: SetupResult) : SetupUiState
    data class Error(val message: String) : SetupUiState
}

class SetupController(
    private val scope: CoroutineScope,
    private val session: YapSession,
) {
    private val _state = MutableStateFlow<SetupUiState>(SetupUiState.Idle)
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun reset() {
        _state.value = SetupUiState.Idle
    }

    fun genesis(accountName: String) = runIntent(SetupIntent.Genesis(accountName))

    fun newAccount(accountName: String) = runIntent(SetupIntent.NewAccountFirstDevice(accountName))

    fun addDevice() = runIntent(SetupIntent.AddDeviceToExistingAccount)

    fun recover(recoveryKey: String, deviceId: String, onion: String, port: Int) {
        val peer = runCatching { PeerId(deviceId.trim()) }.getOrNull()
        val endpoint = runCatching { TorEndpoint(onion.trim(), port) }.getOrNull()
        if (peer == null || endpoint == null) {
            _state.value = SetupUiState.Error("Check the device ID and onion address.")
            return
        }
        runIntent(
            SetupIntent.ImportAccountRecoveryKey(
                recoveryKey.trim(),
                BootstrapEndpoint(peer, endpoint),
            ),
        )
    }

    private fun runIntent(intent: SetupIntent) {
        val orch = session.orchestrator ?: run {
            _state.value = SetupUiState.Error("The app isn't started yet. Retry in a moment.")
            return
        }
        _state.value = SetupUiState.Working
        // Process lifetime, not screen lifetime: setup flips orchestrator state,
        // which navigates away from this screen — a composition scope would cancel
        // init mid-flight and strand the app on "Starting…". Real cancellation
        // still propagates (rethrown, not swallowed as Error).
        scope.launch {
            val result = try {
                orch.completeSetup(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SetupUiState.Error(e.message ?: "Setup failed.")
                return@launch
            }
            _state.value = SetupUiState.Invite(result)
            // The orchestrator is already Running here, so SetupScreen is gone:
            // park the outputs in the session for the App-root overlays.
            session.stageSetupResult(result.recoveryKey, result.inviteBytes)
        }
    }

    /** Wired to the sponsor-side onboarding UI (S3 scan mode). */
    @Suppress("unused")
    fun sponsorErrorMessage(e: Throwable): String = e.message ?: "Couldn't sponsor."
}

/** Wired to the sponsor-side onboarding UI (S3 scan mode). */
@Suppress("unused")
fun sponsorOutcomeMessage(outcome: org.yapyap.orchestrator.runtime.onboarding.SponsorOutcome): String? =
    when (outcome) {
        is org.yapyap.orchestrator.runtime.onboarding.SponsorOutcome.Sponsored ->
            if (outcome.adminGranted) null else "Device added (admin flag didn't apply to this join)."

        is org.yapyap.orchestrator.runtime.onboarding.SponsorOutcome.Refused ->
            RefusalText.sponsor(outcome.reason)
    }
