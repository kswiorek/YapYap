package org.yapyap.backend.integration

import java.nio.file.Files
import java.util.UUID
import org.yapyap.backend.crypto.DefaultIdentityResolver
import org.yapyap.backend.crypto.DefaultSignatureProvider
import org.yapyap.backend.crypto.IdentityKeyServiceConfig
import org.yapyap.backend.crypto.JvmPrivateKeyStore
import org.yapyap.backend.crypto.KmpCryptoProvider
import org.yapyap.backend.db.DatabaseFactory
import org.yapyap.backend.db.DefaultIdentityPublicKeyRepository
import org.yapyap.backend.db.JvmEncryptedDriverFactory
import org.yapyap.backend.db.JvmMasterKeyProvider
import org.yapyap.backend.protection.SignedWebRtcSignalProtection
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.DeviceAddress
import org.yapyap.backend.protocol.EnvelopeRoute
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal
import org.yapyap.backend.transport.webrtc.types.WebRtcSignalKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class Sprint0IntegrationTest {
    @Test
    fun unlocksEncryptedDbWithKeyringAndSignsMockPayloadAcrossReopen() {
        val runId = UUID.randomUUID().toString().replace("-", "")
        val localAddress = DeviceAddress(accountId = "alice-$runId", deviceId = "alice-device-$runId")
        val dbFile = Files.createTempFile("yapyap-s0-$runId-", ".db")
        val masterKeyProvider = JvmMasterKeyProvider(
            serviceName = "yapyap-s0-$runId",
            accountName = "db-master-key",
        )
        val privateKeyStoreServiceName = "yapyap-s0-$runId-private"
        val crypto = KmpCryptoProvider()

        try {
            val firstMasterKey = masterKeyProvider.getOrCreateMasterKey()
            val firstConnection = DatabaseFactory(
                driverFactory = JvmEncryptedDriverFactory(
                    databasePath = dbFile.toAbsolutePath().toString(),
                    masterKey = firstMasterKey,
                ),
            ).createConnection()

            val firstSigning = try {
                val repository = DefaultIdentityPublicKeyRepository(firstConnection.database)
                val identityService = DefaultIdentityResolver(
                    localAddress = localAddress,
                    cryptoProvider = crypto,
                    publicKeyRepository = repository,
                    privateKeyStore = JvmPrivateKeyStore(privateKeyStoreServiceName),
                )
                val signatureProvider = DefaultSignatureProvider(
                    localAddress = localAddress,
                    identityResolver = identityService,
                    cryptoProvider = crypto,
                )
                val payload = "sprint0-mock-payload".encodeToByteArray()
                val signingKeyId = signatureProvider.resolveLocalSigningKeyId()
                val signature = signatureProvider.signDetached(signingKeyId, payload)

                assertTrue(signatureProvider.verifyDetached(localAddress, payload, signature))
                SigningSnapshot(signingKeyId = signingKeyId, payload = payload, signature = signature)
            } finally {
                firstConnection.driver.close()
            }

            val secondMasterKey = masterKeyProvider.getOrCreateMasterKey()
            assertContentEquals(firstMasterKey, secondMasterKey)

            val secondConnection = DatabaseFactory(
                driverFactory = JvmEncryptedDriverFactory(
                    databasePath = dbFile.toAbsolutePath().toString(),
                    masterKey = secondMasterKey,
                ),
            ).createConnection()
            try {
                val repository = DefaultIdentityPublicKeyRepository(secondConnection.database)
                val identityService = DefaultIdentityResolver(
                    localAddress = localAddress,
                    cryptoProvider = crypto,
                    publicKeyRepository = repository,
                    privateKeyStore = JvmPrivateKeyStore(privateKeyStoreServiceName),
                )
                val signatureProvider = DefaultSignatureProvider(
                    localAddress = localAddress,
                    identityResolver = identityService,
                    cryptoProvider = crypto,
                )

                assertEquals(firstSigning.signingKeyId, signatureProvider.resolveLocalSigningKeyId())
                assertTrue(
                    signatureProvider.verifyDetached(
                        source = localAddress,
                        message = firstSigning.payload,
                        signature = firstSigning.signature,
                    )
                )
            } finally {
                secondConnection.driver.close()
            }
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }

    @Test
    fun signedSignalEnvelopeTransmitsOverInMemoryNetworkAndRejectsTampering() {
        val runId = UUID.randomUUID().toString().replace("-", "")
        val aliceAddress = DeviceAddress(accountId = "alice-$runId", deviceId = "alice-device-$runId")
        val bobAddress = DeviceAddress(accountId = "bob-$runId", deviceId = "bob-device-$runId")
        val aliceEndpoint = TorEndpoint(onionFor("a"), 19001)
        val bobEndpoint = TorEndpoint(onionFor("b"), 19002)
        val dbFile = Files.createTempFile("yapyap-s0-network-$runId-", ".db")
        val crypto = KmpCryptoProvider()

        try {
            val connection = DatabaseFactory(
                driverFactory = JvmEncryptedDriverFactory(
                    databasePath = dbFile.toAbsolutePath().toString(),
                    masterKey = ByteArray(32) { i -> (i + 51).toByte() },
                ),
            ).createConnection()

            try {
                val repository = DefaultIdentityPublicKeyRepository(
                    database = connection.database,
                    config = IdentityKeyServiceConfig(
                        defaultOnionAddress = "bootstrap.onion",
                    ),
                )
                val keyStore = JvmPrivateKeyStore("yapyap-s0-network-$runId-private")

                val aliceIdentityService = DefaultIdentityResolver(
                    localAddress = aliceAddress,
                    cryptoProvider = crypto,
                    publicKeyRepository = repository,
                    privateKeyStore = keyStore,
                )
                val bobIdentityService = DefaultIdentityResolver(
                    localAddress = bobAddress,
                    cryptoProvider = crypto,
                    publicKeyRepository = repository,
                    privateKeyStore = keyStore,
                )
                aliceIdentityService.getOrCreateLocalIdentity(aliceAddress)
                bobIdentityService.getOrCreateLocalIdentity(bobAddress)

                val aliceProtection = SignedWebRtcSignalProtection(
                    DefaultSignatureProvider(
                        localAddress = aliceAddress,
                        identityResolver = aliceIdentityService,
                        cryptoProvider = crypto,
                    )
                )
                val bobProtection = SignedWebRtcSignalProtection(
                    DefaultSignatureProvider(
                        localAddress = bobAddress,
                        identityResolver = bobIdentityService,
                        cryptoProvider = crypto,
                    )
                )

                val signal = WebRtcSignal(
                    sessionId = "session-s0-$runId",
                    kind = WebRtcSignalKind.OFFER,
                    source = aliceAddress,
                    target = bobAddress,
                    payload = "offer-sdp".encodeToByteArray(),
                )
                val signedEnvelope = aliceProtection.protect(
                    input = signal,
                    createdAtEpochSeconds = 1_700_001_000L,
                    nonce = byteArrayOf(7, 7, 7, 7),
                )
                val binaryEnvelope = BinaryEnvelope(
                    packetId = PacketId.fromHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                    packetType = PacketType.SIGNAL,
                    createdAtEpochSeconds = 1_700_001_000L,
                    expiresAtEpochSeconds = 1_700_001_300L,
                    hopCount = 0,
                    route = EnvelopeRoute(
                        destinationAccount = bobAddress.accountId,
                        destinationDevice = bobAddress.deviceId,
                        nextHopDevice = null,
                    ),
                    payload = signedEnvelope.encode(),
                )

                val network = InMemoryEnvelopeNetwork()
                network.deliver(aliceEndpoint, bobEndpoint, binaryEnvelope)

                val received = network.receive(bobEndpoint)
                assertEquals(PacketType.SIGNAL, received.packetType)

                val decodedSignedEnvelope = WebRtcSignalEnvelope.decode(received.payload)
                val opened = bobProtection.open(decodedSignedEnvelope)
                assertEquals(signal.sessionId, opened.sessionId)
                assertEquals(signal.source, opened.source)
                assertEquals(signal.target, opened.target)
                assertContentEquals(signal.payload, opened.payload)

                val tampered = decodedSignedEnvelope.copy(
                    protectedPayload = "tampered".encodeToByteArray(),
                )
                assertFails {
                    bobProtection.open(tampered)
                }
            } finally {
                connection.driver.close()
            }
        } finally {
            Files.deleteIfExists(dbFile)
        }
    }
}

private data class SigningSnapshot(
    val signingKeyId: String,
    val payload: ByteArray,
    val signature: ByteArray,
)

private class InMemoryEnvelopeNetwork {
    private val inboxes = mutableMapOf<TorEndpoint, MutableList<BinaryEnvelope>>()

    fun deliver(source: TorEndpoint, target: TorEndpoint, envelope: BinaryEnvelope) {
        source.hashCode()
        val inbox = inboxes.getOrPut(target) { mutableListOf() }
        inbox += envelope
    }

    fun receive(target: TorEndpoint): BinaryEnvelope {
        val inbox = inboxes[target] ?: error("No inbox for target=$target")
        check(inbox.isNotEmpty()) { "No messages for target=$target" }
        return inbox.removeAt(0)
    }
}

private fun onionFor(seed: String): String {
    val host = seed.repeat(56).take(56)
    return "$host.onion"
}
