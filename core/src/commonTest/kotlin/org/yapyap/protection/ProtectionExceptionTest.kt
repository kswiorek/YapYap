package org.yapyap.protection

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.CryptoSessionException
import org.yapyap.protocol.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtectionExceptionTest {

    @Test
    fun mapCryptoSessionException_handshakeMismatchIsDeferredGap() {
        val mapped = ProtectionException.mapCryptoSessionException(
            CryptoSessionException.HandshakeMismatch("sessionEpoch mismatch"),
        )
        assertIs<ProtectionException.SessionNotReady>(mapped)
        assertEquals(ProtectionDisposition.DEFER, mapped.disposition)
    }

    @Test
    fun mapCryptoSessionException_messageSkipExceededIsPermanentViolation() {
        val mapped = ProtectionException.mapCryptoSessionException(
            CryptoSessionException.MessageSkipExceeded(recvMessageNumber = 1, until = 400),
        )
        assertIs<ProtectionException.SessionViolation>(mapped)
        assertEquals(ProtectionDisposition.PERMANENT, mapped.disposition)
    }

    @Test
    fun mapCryptoSessionException_replayIsPermanentViolation() {
        val mapped = ProtectionException.mapCryptoSessionException(
            CryptoSessionException.Replay(messageNumber = 3),
        )
        assertIs<ProtectionException.SessionViolation>(mapped)
        assertEquals(ProtectionDisposition.PERMANENT, mapped.disposition)
    }

    @Test
    fun mapCryptoSessionException_supersededDhChainIsDeferredGap() {
        val mapped = ProtectionException.mapCryptoSessionException(
            CryptoSessionException.SupersededDhChain(messageNumber = 3),
        )
        assertIs<ProtectionException.SessionGap>(mapped)
        assertEquals(ProtectionDisposition.DEFER, mapped.disposition)
    }

    @Test
    fun mapCryptoSessionException_noSessionIsDeferred() {
        val mapped = ProtectionException.mapCryptoSessionException(
            CryptoSessionException.NoSession(PeerId("peer-a"), sessionEpoch = 1),
        )
        assertIs<ProtectionException.SessionNotReady>(mapped)
        assertEquals(ProtectionDisposition.DEFER, mapped.disposition)
    }

    @Test
    fun map_wrapsCryptoExceptionAsIdentityNotReady() {
        val mapped = ProtectionException.map(
            CryptoException.MissingDeviceRecord("peer-a"),
        )
        assertIs<ProtectionException.IdentityNotReady>(mapped)
        assertEquals(ProtectionDisposition.DEFER, mapped.disposition)
    }

    @Test
    fun mapEncryptFailure_unknownErrorIsDeferredNotPermanent() {
        // Encrypt-side unknown failures are environmental until proven otherwise:
        // the message is kept (DEFER), never dropped as permanently dead.
        val mapped = ProtectionException.mapEncryptFailure(
            IllegalStateException("db unavailable"),
        )
        assertIs<ProtectionException.EncryptNotReady>(mapped)
        assertEquals(ProtectionDisposition.DEFER, mapped.disposition)
    }

    @Test
    fun mapEncryptDecryptFailure_unknownErrorIsPermanent() {
        // Decrypt-side default is unchanged: unknown input is treated as bad input.
        val mapped = ProtectionException.mapEncryptDecryptFailure(
            IllegalStateException("garbage"),
        )
        assertIs<ProtectionException.AuthenticationFailed>(mapped)
        assertEquals(ProtectionDisposition.PERMANENT, mapped.disposition)
    }
}
