package org.yapyap.routing.file.large

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.LargeFileDeclineReason
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.LargeFileAdmission
import org.yapyap.routing.file.fileGuarded
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.router.DeviceDirectory

/**
 * The large-file serve gate (design §6.6, §9): the visibility rule, the device/account statuses and
 * the held-file check, mirroring [org.yapyap.routing.file.relay.RelayDoor]'s admission. It only
 * decides; the holder performs the wire answers. It fails closed: a lookup that throws declines.
 */
internal class LargeFileAdmissionGate(
    private val devices: DeviceDirectory,
    private val requests: FileRequestSource,
    private val plaintext: FilePlaintextSource,
) {
    suspend fun admit(peer: PeerId, fileId: FileId, fromOffset: Long): LargeFileAdmission {
        val fields = mapOf("fileId" to fileId, "peer" to peer)
        val account = fileGuarded("Large file serve gate lookup failed; declining", fields) {
            allowedAccount(peer, fileId)
        } ?: return LargeFileAdmission.Declined(LargeFileDeclineReason.NOT_ALLOWED)
        val manifest = fileGuarded("Large file describe failed; declining", fields) {
            plaintext.describe(fileId)
        } ?: return LargeFileAdmission.Declined(LargeFileDeclineReason.NOT_HELD)
        if (fromOffset > manifest.totalBytes) {
            return LargeFileAdmission.Abort(LargeFileAbortReason.SOURCE_ERROR, "bad offset")
        }
        return LargeFileAdmission.Allowed(account, manifest)
    }

    /** [peer]'s account when it may be served [fileId], or null. */
    private suspend fun allowedAccount(peer: PeerId, fileId: FileId): AccountId? {
        val account = devices.getAccountIdForDevice(peer) ?: return null
        // Removed or revoked devices never serve, even when the visibility rule would allow them.
        if (devices.getDeviceStatus(peer) != IdentityStatus.ACTIVE) return null
        if (devices.getAccountStatus(account) == IdentityStatus.BANNED) return null
        return account.takeIf { requests.canServe(fileId, account) }
    }
}
