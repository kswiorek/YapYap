package org.yapyap.protection

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.protection.envelope.PlaintextMessageProtection
import org.yapyap.protection.envelope.PlaintextSystemProtection
import org.yapyap.protection.envelope.PlaintextWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedMessageProtection
import org.yapyap.protection.envelope.SignedSystemProtection
import org.yapyap.protection.envelope.SignedWebRtcSignalProtection
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.SignalSecurityScheme

class DefaultEnvelopeProtectionServiceTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun defaultService_signal_file_message_roundTrip_tableDriven() = runTest {
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
                    webRtcSignalProtection = PlaintextWebRtcSignalProtection(crypto),
                    fileProtection = PassthroughFileProtection(),
                    messageProtection = PlaintextMessageProtection(crypto),
                    systemProtection = PlaintextSystemProtection(crypto),
                ),
                scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            ),
            Row(
                name = "signal_signed",
                service = DefaultEnvelopeProtectionService(
                    webRtcSignalProtection = SignedWebRtcSignalProtection(signatureProvider, crypto),
                    fileProtection = PassthroughFileProtection(),
                    messageProtection = SignedMessageProtection(signatureProvider, crypto),
                    systemProtection = SignedSystemProtection(signatureProvider, crypto),
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

            val ack = samplePacketAckPayload()
            val ackOut = row.service.openSystem(row.service.protectSystem(ack, ctx))
            assertEquals(ack, ackOut, "system ack ${row.name}")

            val nack = samplePacketNackPayload()
            val nackOut = row.service.openSystem(row.service.protectSystem(nack, ctx))
            assertEquals(nack, nackOut, "system nack ${row.name}")
        }
    }

    @Test
    fun defaultService_decryptFileChunk_delegatesToFileProtection() = runTest {
        val fileProtection = PassthroughFileProtection()
        val service = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = PlaintextWebRtcSignalProtection(crypto),
            fileProtection = fileProtection,
            messageProtection = PlaintextMessageProtection(crypto),
            systemProtection = PlaintextSystemProtection(crypto),
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
