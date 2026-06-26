package org.yapyap.backend.db

import org.yapyap.backend.crypto.e2ee.RatchetSessionState
import org.yapyap.backend.crypto.e2ee.X3dhMode
import org.yapyap.backend.protocol.PeerId

data class CryptoSessionRecord(
    val peerDeviceId: PeerId,
    val sessionEpoch: Int,
    val ratchetState: RatchetSessionState,
    val meta: CryptoSessionMeta,
    val canonical: Boolean,
)

data class CryptoSessionMeta(
    val role: SessionRole,
    val x3dhMode: X3dhMode,
    val handshakeSpkId: String,
    val handshakeOpkId: String? = null,
    val initiatorEphemeralPrivateKey: ByteArray? = null,
    val initiatorEphemeralPublicKey: ByteArray? = null,
    val offeredOpkId: String? = null,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
)

enum class SessionRole { INITIATOR, RESPONDER }
enum class SessionStatus { ACTIVE, SUPERSEDED }

interface CryptoSessionStore {
    suspend fun loadCanonical(peerDeviceId: PeerId, sessionEpoch: Int): CryptoSessionRecord?

    suspend fun loadSessions(peerDeviceId: PeerId, sessionEpoch: Int): List<CryptoSessionRecord>

    suspend fun save(record: CryptoSessionRecord)

    suspend fun setCanonical(peerDeviceId: PeerId, sessionEpoch: Int, role: SessionRole, canonical: Boolean)

    /** Highest epoch to use for new outbound encrypt (e.g. 2 if epoch-2 row exists). */
    suspend fun latestEncryptEpoch(peerDeviceId: PeerId): Int?

    suspend fun listByPeer(peerDeviceId: PeerId): List<CryptoSessionRecord>

    suspend fun markSuperseded(peerDeviceId: PeerId, sessionEpoch: Int)
}