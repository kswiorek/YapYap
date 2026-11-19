package org.yapyap.routing.file

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.MapBackedCryptoSessionStore
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.e2ee.managerForPeer
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.protection.FakeIdentityResolverForProtection
import org.yapyap.protection.envelope.*
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.router.InMemoryFileIncomingTransferStore
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.store.FileIncomingStatus
import org.yapyap.routing.store.FileIncomingTransfer
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.InMemoryOpkRepository
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DefaultFileCarrierTest {

    private val crypto = DefaultCryptoProvider()
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val otherFileId = FileId("f3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

    private data class Harness(
        val carrier: DefaultFileCarrier,
        val service: DefaultEnvelopeProtectionService,
        val incoming: InMemoryFileIncomingTransferStore,
        val sent: MutableList<org.yapyap.protocol.envelopes.BinaryEnvelope>,
        val localId: PeerId,
    )

    private suspend fun harness(): Harness {
        val localPeer = buildTestPeerIdentity(crypto, "fc-local")
        val authorPeer = buildTestPeerIdentity(crypto, "fc-author")
        val localSession = managerForPeer(
            crypto, localPeer, authorPeer,
            MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto),
        )
        managerForPeer(
            crypto, authorPeer, localPeer,
            MapBackedCryptoSessionStore(), InMemoryOpkRepository(crypto),
        )
        val protection = SignedAndEncryptedFileProtection(
            FakeIdentityResolverForProtection(localPeer.signingPrivateKey, emptyMap())
                .let { DefaultSignatureProvider(it, it, crypto) },
            localSession,
            crypto,
        )
        val service = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = PlaintextWebRtcSignalProtection(crypto),
            fileProtection = protection,
            messageProtection = PlaintextMessageProtection(crypto),
            systemProtection = PlaintextSystemProtection(crypto),
            bootstrapProtection = BootstrapProtection(
                crypto,
                org.yapyap.crypto.store.BootstrapKeySource { null },
            ),
        )
        val incoming = InMemoryFileIncomingTransferStore()
        val sent = mutableListOf<org.yapyap.protocol.envelopes.BinaryEnvelope>()
        val carrier = DefaultFileCarrier(
            localDeviceId = localPeer.device.deviceId,
            protection = service,
            incoming = incoming,
            enqueue = { sent += it },
            clock = FakeClock(epochSeconds(10_000L)),
            routerConfig = MutableStateFlow(RouterConfig()),
            pushOps = org.yapyap.routing.router.NoopFilePushOps(),
            events = MutableSharedFlow(extraBufferCapacity = 64),
        )
        return Harness(carrier, service, incoming, sent, localPeer.device.deviceId)
    }

    private fun row(
        file: FileId,
        author: PeerId,
        transferId: TransferId = TransferId(Uuid.random()),
    ): FileIncomingTransfer = FileIncomingTransfer(
        transferId = transferId,
        fileId = file,
        authorDevice = author,
        key = ByteArray(32),
        chunkSizeBytes = 220 * 1024,
        totalBytes = 1000L,
        startIndex = 0,
        status = FileIncomingStatus.ACTIVE,
        createdAt = epochSeconds(10_000L),
        expiresAt = epochSeconds(10_000L) + 2.days,
    )

    @Test
    fun fileReady_completesEveryAuthorDropsRowsAndEmitsReady() = runTest {
        val h = harness()
        val authorA = PeerId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val authorB = PeerId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        h.incoming.insert(row(fileId, authorA))
        h.incoming.insert(row(fileId, authorB))
        h.incoming.insert(row(otherFileId, authorA))

        val events = mutableListOf<FileEvent>()
        backgroundScope.launch { h.carrier.fileEvents.collect { events += it } }
        runCurrent()
        h.carrier.fileReady(fileId)
        runCurrent()

        assertEquals(2, h.sent.size, "one COMPLETE per live transfer author")
        assertEquals(setOf(authorA, authorB), h.sent.map { it.target }.toSet())
        h.sent.forEach { env ->
            val fileEnv = FileEnvelope.decode(env.payload)
            assertEquals(h.localId, fileEnv.source)
            assertIs<FilePayload.Complete>(fileEnv.decodePayload())
        }
        assertTrue(h.incoming.findByFile(fileId).isEmpty(), "rows are dropped on COMPLETE")
        assertEquals(1, h.incoming.findByFile(otherFileId).size, "other files untouched")
        assertEquals<List<FileEvent>>(listOf(FileEvent.Ready(fileId)), events)
    }

    @Test
    fun fileReady_withoutRows_emitsReadyWithoutSending() = runTest {
        val h = harness()
        val events = mutableListOf<FileEvent>()
        backgroundScope.launch { h.carrier.fileEvents.collect { events += it } }
        runCurrent()
        h.carrier.fileReady(fileId)
        runCurrent()
        assertTrue(h.sent.isEmpty())
        assertEquals<List<FileEvent>>(listOf(FileEvent.Ready(fileId)), events)
    }

    @Test
    fun emit_forwardsToFlow() = runTest {
        val h = harness()
        val progress = FileEvent.IncomingProgress(fileId, 100L, 1000L)
        val events = mutableListOf<FileEvent>()
        backgroundScope.launch { h.carrier.fileEvents.collect { events += it } }
        runCurrent()
        h.carrier.emit(progress)
        runCurrent()
        assertEquals<List<FileEvent>>(listOf(progress), events)
    }

    @Test
    fun push_delegatesToPushOps() = runTest {
        val h = harness()
        var pushed: Pair<FileId, AccountId>? = null
        val carrier = DefaultFileCarrier(
            localDeviceId = h.localId,
            protection = h.service,
            incoming = h.incoming,
            enqueue = { h.sent += it },
            clock = FakeClock(epochSeconds(10_000L)),
            routerConfig = MutableStateFlow(RouterConfig()),
            pushOps = object : org.yapyap.routing.file.FilePushOps {
                override suspend fun pushFile(fileId: FileId, target: AccountId) {
                    pushed = fileId to target
                }
                override suspend fun cancelFile(fileId: FileId, target: AccountId) = Unit
                override suspend fun serveFile(
                    fileId: FileId,
                    device: PeerId,
                    fromOffset: Long,
                ): Boolean = false
            },
            events = MutableSharedFlow(extraBufferCapacity = 64),
        )
        val target = AccountId("target-account")
        carrier.pushFile(fileId, target)
        assertEquals(fileId to target, pushed)
    }
}
