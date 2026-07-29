package org.yapyap.routing.router

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.time.FixedEpochSecondsProvider
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.TorIncomingEnvelope
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Sprint 1 acceptance: two routers deliver a text message with real Double Ratchet
 * encryption through the full protect → Tor → open path (recording transport, no live mesh).
 */
class DefaultRouterE2eeIntegrationTest {

    @Test
    fun twoRouters_encryptedMessage_deliveredAndDecrypted() = runBlocking {
        val fixture = buildE2eeTwoRouterFixture()
        val outbound = sampleE2eeTextPayload()

        fixture.use {
            it.aliceRouter.start()
            it.bobRouter.start()

            val received = coroutineScope {
                val waitInbound = async { it.bobRouter.incomingMessages.first() }
                delay(200.milliseconds)

                val sendResult = it.aliceRouter.sendMessage(it.bobAccount, outbound, RouterTransport.TOR)
                assertEquals(SendMessageStatus.SUCCESS, sendResult.status)
                assertEquals(1, sendResult.peersQueued)

                val (_, binaryEnvelope) = it.aliceTor.sends.single()
                assertEncryptedWireEnvelope(binaryEnvelope, outbound)

                it.deliverAliceToBob(binaryEnvelope)
                waitInbound.await()
            }

            val text = assertIs<MessagePayload.Text>(received)
            assertEquals(outbound.messageId, text.messageId)
            assertEquals(outbound.text, text.text)
            assertEquals(outbound.roomId, text.roomId)
        }
    }

    @Test
    fun twoRouters_offlineRecipient_encryptedMessage_decryptedWhenDelivered() = runBlocking {
        val fixture = buildE2eeTwoRouterFixture()
        val outbound = sampleE2eeTextPayload()

        fixture.use {
            it.aliceRouter.start()

            val sendResult = it.aliceRouter.sendMessage(it.bobAccount, outbound, RouterTransport.TOR)
            assertEquals(SendMessageStatus.SUCCESS, sendResult.status)
            assertEquals(1, it.aliceTor.sends.size)

            val binaryEnvelope = it.aliceTor.sends.single().second
            assertEncryptedWireEnvelope(binaryEnvelope, outbound)

            it.bobRouter.start()
            it.deliverAliceToBob(binaryEnvelope)

            val received = withTimeout(5.seconds) {
                it.bobRouter.incomingMessages.first()
            }
            val text = assertIs<MessagePayload.Text>(received)
            assertEquals(outbound.text, text.text)
        }
    }

    private fun assertEncryptedWireEnvelope(
        binaryEnvelope: org.yapyap.protocol.envelopes.BinaryEnvelope,
        outbound: MessagePayload.Text,
    ) {
        val messageEnvelope = MessageEnvelope.decode(binaryEnvelope.payload)
        assertEquals(SignalSecurityScheme.ENCRYPTED_AND_SIGNED, messageEnvelope.securityScheme)
        assertNotNull(messageEnvelope.signature)
        assertTrue(
            !messageEnvelope.payload.contentEquals(outbound.encode()),
            "wire payload must not be cleartext MessagePayload bytes",
        )
    }

    private suspend fun E2eeTwoRouterFixture.use(block: suspend (E2eeTwoRouterFixture) -> Unit) {
        try {
            block(this)
        } finally {
            runCatching { aliceRouter.stop() }
            runCatching { bobRouter.stop() }
        }
    }

    private suspend fun buildE2eeTwoRouterFixture(): E2eeTwoRouterFixture {
        val crypto = KmpCryptoProvider()
        val alicePeer = buildTestPeerIdentity(crypto, "router-e2ee-alice")
        val bobPeer = buildTestPeerIdentity(crypto, "router-e2ee-bob")
        val bobAccount = AccountId("bob-e2ee-account")
        val time = FixedEpochSecondsProvider(10_000L)

        val aliceTor = RecordingTorTransport(TorEndpoint("alice-e2ee.onion", 80))
        val bobTor = RecordingTorTransport(TorEndpoint("bob-e2ee.onion", 80))

        val torByAlice = mutableMapOf(bobPeer.device.deviceId to bobTor.advertisedEndpoint)
        val torByBob = mutableMapOf(alicePeer.device.deviceId to aliceTor.advertisedEndpoint)

        val aliceStack = buildE2eeRouterStack(
            local = alicePeer,
            remote = bobPeer,
            peersByAccount = mapOf(bobAccount to listOf(bobPeer.device.deviceId)),
            torByPeer = torByAlice,
            time = time,
            crypto = crypto,
        )
        val bobStack = buildE2eeRouterStack(
            local = bobPeer,
            remote = alicePeer,
            peersByAccount = emptyMap(),
            torByPeer = torByBob,
            time = time,
            crypto = crypto,
        )

        return E2eeTwoRouterFixture(
            aliceRouter = e2eeRouterUnderTest(aliceStack, aliceTor, time = time),
            bobRouter = e2eeRouterUnderTest(bobStack, bobTor, time = time),
            aliceTor = aliceTor,
            bobTor = bobTor,
            bobAccount = bobAccount,
            alicePeerId = alicePeer.device.deviceId,
        )
    }

    private class E2eeTwoRouterFixture(
        val aliceRouter: DefaultRouter,
        val bobRouter: DefaultRouter,
        val aliceTor: RecordingTorTransport,
        val bobTor: RecordingTorTransport,
        val bobAccount: AccountId,
        val alicePeerId: PeerId,
    ) {
        fun deliverAliceToBob(binaryEnvelope: org.yapyap.protocol.envelopes.BinaryEnvelope) {
            bobTor.tryEmitIncoming(TorIncomingEnvelope(aliceTor.advertisedEndpoint, binaryEnvelope))
        }
    }
}

private fun sampleE2eeTextPayload(): MessagePayload.Text =
    MessagePayload.Text(
        messageId = Uuid.random(),
        roomId = "room-e2ee-integration",
        senderAccountId = AccountId("alice-e2ee-account"),
        prevId = null,
        lamportClock = 1L,
        createdAtEpochSeconds = 0L,
        text = "hello-e2ee-router",
        authorDeviceId = PeerId("test-device"),
    )
