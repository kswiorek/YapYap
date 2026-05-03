package org.yapyap.backend.protection

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.yapyap.backend.crypto.DefaultSignatureProvider
import org.yapyap.backend.crypto.KmpCryptoProvider
import org.yapyap.backend.protocol.FilePayload
import org.yapyap.backend.protocol.SignalSecurityScheme

class DefaultEnvelopeProtectionServiceTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun defaultService_signal_file_message_roundTrip_tableDriven() {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(sourcePeer to record),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, crypto)

        data class Row(
            val name: String,
            val service: DefaultEnvelopeProtectionService,
            val scheme: SignalSecurityScheme,
        )

        val rows = listOf(
            Row(
                name = "signal_plaintext",
                service = DefaultEnvelopeProtectionService(
                    webRtcSignalProtection = PlaintextWebRtcSignalProtection(),
                    fileProtection = PassthroughFileProtection(),
                    messageProtection = PlaintextMessageProtection(),
                ),
                scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            ),
            Row(
                name = "signal_signed",
                service = DefaultEnvelopeProtectionService(
                    webRtcSignalProtection = SignedWebRtcSignalProtection(signatureProvider),
                    fileProtection = PassthroughFileProtection(),
                    messageProtection = SignedMessageProtection(signatureProvider),
                ),
                scheme = SignalSecurityScheme.SIGNED,
            ),
        )

        for (row in rows) {
            val ctx = sampleEnvelopeContext(
                scheme = row.scheme,
                source = sourcePeer,
                target = targetPeer,
            )

            val signal = sampleWebRtcSignal(sourcePeer, targetPeer)
            val signalOut = row.service.openSignal(row.service.protectSignal(signal, ctx))
            assertEquals(signal.sessionId, signalOut.sessionId, "signal ${row.name} sessionId")
            assertContentEquals(signal.payload, signalOut.payload, "signal ${row.name} payload")

            val file = sampleFileOfferPayload()
            val fileOut = row.service.openFile(row.service.protectFile(file, ctx))
            assertEquals(file, fileOut.payload, "file ${row.name}")

            val text = sampleTextPayload("m-${row.name}")
            val messageOut = row.service.openMessage(row.service.protectMessage(text, ctx))
            assertEquals(text, messageOut, "message ${row.name}")
        }
    }

    @Test
    fun defaultService_decryptFileChunk_delegatesToFileProtection() {
        val fileProtection = PassthroughFileProtection()
        val service = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = PlaintextWebRtcSignalProtection(),
            fileProtection = fileProtection,
            messageProtection = PlaintextMessageProtection(),
        )
        val chunk = FilePayload.EncryptedChunk(
            chunkIndex = 0,
            chunkCount = 2,
            chunkCiphertext = byteArrayOf(0xab.toByte()),
        )
        val plain = service.decryptFileChunk(chunk)
        assertContentEquals(chunk.chunkCiphertext, plain.fileData)
    }
}
