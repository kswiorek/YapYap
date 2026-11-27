package org.yapyap.routing.file.large

import kotlinx.coroutines.CancellationException
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.LargeFileDeclineReason
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.LargeFileAdmission
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.router.DeviceDirectory

/**
 * The large-file serve gate (design §6.6, §9): the visibility rule, the device/account statuses and
 * the held-file check, mirroring [org.yapyap.routing.file.relay.RelayDoor]'s admission. It only
 * decides; the holder performs the wire answers.
 */
internal class LargeFileAdmissionGate(
    private val devices: DeviceDirectory,
    private val requests: FileRequestSource,
    private val plaintext: FilePlaintextSource,
) {
    suspend fun admit(peer: PeerId, fileId: FileId, fromOffset: Long): LargeFileAdmission {
        val account = try {
            devices.getAccountIdForDevice(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch serve gate lookup failed",
                throwable = e,
            )
            null
        } ?: return LargeFileAdmission.Declined(LargeFileDeclineReason.NOT_ALLOWED)

        // Removed or revoked devices never serve, even when the visibility rule would allow them.
        // Fail closed: a directory failure declines rather than serving an unknown peer.
        val deviceStatus = try {
            devices.getDeviceStatus(peer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch serve device-status lookup failed; declining",
                throwable = e,
                fields = mapOf("fileId" to fileId),
            )
            return LargeFileAdmission.Declined(LargeFileDeclineReason.NOT_ALLOWED)
        }
        val accountStatus = try {
            devices.getAccountStatus(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch serve account-status lookup failed; declining",
                throwable = e,
                fields = mapOf("fileId" to fileId),
            )
            return LargeFileAdmission.Declined(LargeFileDeclineReason.NOT_ALLOWED)
        }
        if (deviceStatus != IdentityStatus.ACTIVE || accountStatus == IdentityStatus.BANNED) {
            return LargeFileAdmission.Declined(LargeFileDeclineReason.NOT_ALLOWED)
        }

        val allowed = try {
            requests.canServe(fileId, account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch serve gate failed",
                throwable = e,
                fields = mapOf("fileId" to fileId),
            )
            false
        }
        if (!allowed) return LargeFileAdmission.Declined(LargeFileDeclineReason.NOT_ALLOWED)

        val info = try {
            plaintext.describe(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Fetch describe failed",
                throwable = e,
                fields = mapOf("fileId" to fileId),
            )
            null
        } ?: return LargeFileAdmission.Declined(LargeFileDeclineReason.NOT_HELD)

        if (fromOffset > info.totalBytes) {
            return LargeFileAdmission.Abort(LargeFileAbortReason.SOURCE_ERROR, "bad offset")
        }
        return LargeFileAdmission.Allowed(account, info)
    }
}
