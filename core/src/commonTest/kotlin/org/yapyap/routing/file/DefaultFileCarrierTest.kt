package org.yapyap.routing.file

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.file.FilePushOps
import org.yapyap.routing.router.NoopFilePushOps
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
import org.yapyap.protection.envelope.SignedAndEncryptedFileProtection
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.TransferKey
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileReceiveEnd
import org.yapyap.routing.file.port.FileSendState
import org.yapyap.routing.router.InMemoryFileIncomingTransferStore
import org.yapyap.routing.router.RouterConfig
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

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultFileCarrierTest {

    private val crypto = DefaultCryptoProvider()
    private val fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private val otherFileId = FileId("f3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

    private data class Harness(
        val carrier: DefaultFileCarrier,
        val protection: SignedAndEncryptedFileProtection,
        val incoming: InMemoryFileIncomingTransferStore,
        val sent: MutableList<BinaryEnvelope>,
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
        val store = InMemoryFileIncomingTransferStore()
        val clock = FakeClock(epochSeconds(10_000L))
        val incoming = FileIncomingTransfers(store, clock)
        val sent = mutableListOf<BinaryEnvelope>()
        val carrier = DefaultFileCarrier(
            localDeviceId = localPeer.device.deviceId,
            protection = protection,
            incoming = incoming,
            enqueue = { sent += it },
            clock = clock,
            routerConfig = MutableStateFlow(RouterConfig()),
            pushOps = NoopFilePushOps(),
            events = MutableSharedFlow(extraBufferCapacity = 64),
            startRequest = { _, _ -> },
            abortFetch = { _, _ -> },
        )
        return Harness(carrier, protection, store, sent, localPeer.device.deviceId)
    }

    private fun row(
        file: FileId,
        author: PeerId,
        transferId: TransferId = TransferId(Uuid.random()),
    ): FileIncomingTransfer = FileIncomingTransfer(
        transferId = transferId,
        fileId = file,
        authorDevice = author,
        key = TransferKey(ByteArray(32)),
        chunkSizeBytes = 220 * 1024,
        totalBytes = 1000L,
        startIndex = 0,
        createdAt = epochSeconds(10_000L),
        expiresAt = epochSeconds(10_000L) + 2.days,
    )

    @Test
    fun readyEnd_completesEveryAuthorAndDropsRows() = runTest {
        val h = harness()
        val authorA = PeerId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val authorB = PeerId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        h.incoming.insert(row(fileId, authorA))
        h.incoming.insert(row(fileId, authorB))
        h.incoming.insert(row(otherFileId, authorA))

        val events = mutableListOf<FileEvent>()
        backgroundScope.launch { h.carrier.fileEvents.collect { events += it } }
        runCurrent()
        h.carrier.onReceiveEnd(FileReceiveEnd.Ready(fileId))
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
        assertEquals(emptyList(), events, "readiness is the receive engine's to report")
    }

    @Test
    fun readyEnd_withoutRows_sendsNothing() = runTest {
        val h = harness()
        val events = mutableListOf<FileEvent>()
        backgroundScope.launch { h.carrier.fileEvents.collect { events += it } }
        runCurrent()
        h.carrier.onReceiveEnd(FileReceiveEnd.Ready(fileId))
        runCurrent()
        assertTrue(h.sent.isEmpty())
        assertEquals(emptyList(), events)
    }

    @Test
    fun cancelledEnd_cancelsEveryAuthorWithItsReason() = runTest {
        val h = harness()
        val author = PeerId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        h.incoming.insert(row(fileId, author))

        h.carrier.onReceiveEnd(FileReceiveEnd.Cancelled(fileId, FileCancelReason.NO_SPACE))

        val cancel = assertIs<FilePayload.Cancel>(FileEnvelope.decode(h.sent.single().payload).decodePayload())
        assertEquals(FileCancelReason.NO_SPACE, cancel.reason)
        assertTrue(h.incoming.findByFile(fileId).isEmpty())
    }

    @Test
    fun push_delegatesToPushOps() = runTest {
        val h = harness()
        var pushed: Pair<FileId, AccountId>? = null
        val carrier = DefaultFileCarrier(
            localDeviceId = h.localId,
            protection = h.protection,
            incoming = FileIncomingTransfers(h.incoming, FakeClock(epochSeconds(10_000L))),
            enqueue = { h.sent += it },
            clock = FakeClock(epochSeconds(10_000L)),
            routerConfig = MutableStateFlow(RouterConfig()),
            pushOps = object : FilePushOps {
                override suspend fun pushFile(fileId: FileId, target: AccountId) {
                    pushed = fileId to target
                }
                override suspend fun cancelFile(fileId: FileId, target: AccountId) = Unit
                override suspend fun serveFile(
                    fileId: FileId,
                    device: PeerId,
                    fromOffset: Long,
                ): Boolean = false
                override suspend fun sendState(fileId: FileId): Map<AccountId, FileSendState> =
                    emptyMap()
            },
            events = MutableSharedFlow(extraBufferCapacity = 64),
            startRequest = { _, _ -> },
            abortFetch = { _, _ -> },
        )
        val target = AccountId("target-account")
        carrier.pushFile(fileId, target)
        assertEquals(fileId to target, pushed)
    }
}
