package org.yapyap.protection.service

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.crypto.store.BootstrapKeySource
import org.yapyap.protection.FakeIdentityResolverForProtection
import org.yapyap.protection.deviceRecordFor
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.PlaintextMessageProtection
import org.yapyap.protection.envelope.PlaintextSystemProtection
import org.yapyap.protection.envelope.PlaintextWebRtcSignalProtection
import org.yapyap.protection.envelope.SignedMessageProtection
import org.yapyap.protection.envelope.SignedSystemProtection
import org.yapyap.protection.envelope.SignedWebRtcSignalProtection
import org.yapyap.protection.sampleEnvelopeContext
import org.yapyap.protection.samplePacketAckPayload
import org.yapyap.protection.samplePacketNackPayload
import org.yapyap.protection.samplePeerTriplet
import org.yapyap.protection.sampleTextPayload
import org.yapyap.protection.sampleWebRtcSignal
import org.yapyap.protocol.SignalSecurityScheme
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DefaultEnvelopeProtectionServiceTest {

    private val crypto = DefaultCryptoProvider()

    @Test
    fun defaultService_signal_file_message_roundTrip_tableDriven() = runTest {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(sourcePeer to record),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, resolver, crypto)

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
                    messageProtection = PlaintextMessageProtection(crypto),
                    systemProtection = PlaintextSystemProtection(crypto),
                    bootstrapProtection = BootstrapProtection(crypto, BootstrapKeySource { null }),
                ),
                scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            ),
            Row(
                name = "signal_signed",
                service = DefaultEnvelopeProtectionService(
                    webRtcSignalProtection = SignedWebRtcSignalProtection(signatureProvider, crypto),
                    messageProtection = SignedMessageProtection(signatureProvider, crypto),
                    systemProtection = SignedSystemProtection(signatureProvider, crypto),
                    bootstrapProtection = BootstrapProtection(crypto, BootstrapKeySource { null }),
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
            assertEquals(signal.target, signalOut.target, "signal ${row.name} sessionId")
            assertContentEquals(signal.payload, signalOut.payload, "signal ${row.name} payload")

            val text = sampleTextPayload()
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
}
