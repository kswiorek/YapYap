package org.yapyap.protection

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.CryptoSessionException
import org.yapyap.protocol.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtectionExceptionTest {

    @Test
    fun mapCryptoSessionException_handshakeMismatchIsRetryable() {
        val mapped = ProtectionException.mapCryptoSessionException(
            CryptoSessionException.HandshakeMismatch("sessionEpoch mismatch"),
        )
        assertIs<ProtectionException.SessionNotReady>(mapped)
        assertEquals(ProtectionDisposition.RETRYABLE, mapped.disposition)
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
    fun mapCryptoSessionException_noSessionIsRetryable() {
        val mapped = ProtectionException.mapCryptoSessionException(
            CryptoSessionException.NoSession(PeerId("peer-a"), sessionEpoch = 1),
        )
        assertIs<ProtectionException.SessionNotReady>(mapped)
        assertEquals(ProtectionDisposition.RETRYABLE, mapped.disposition)
    }

    @Test
    fun map_wrapsCryptoExceptionAsIdentityNotReady() {
        val mapped = ProtectionException.map(
            CryptoException.MissingDeviceRecord("peer-a"),
        )
        assertIs<ProtectionException.IdentityNotReady>(mapped)
        assertEquals(ProtectionDisposition.RETRYABLE, mapped.disposition)
    }
}
