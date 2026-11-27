package org.yapyap.routing.file.transfer

import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.TransferShape
import org.yapyap.routing.file.TransferOffer
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.PlaintextRead
import org.yapyap.routing.file.port.PlaintextStream
import org.yapyap.routing.store.FileRelayStore
import org.yapyap.routing.store.OutgoingSeal
import org.yapyap.routing.store.RelayTransfer

/**
 * Where an outgoing transfer's OFFER and chunk ciphertext come from (docs/envelope-redesign.md
 * §5.1). The transfer machinery only moves what the source hands it: the origin encrypts
 * plaintext ([SealChunkSource]), a relay reads what it stored ([RelayChunkSource]).
 */
internal interface ChunkSource : AutoCloseable {
    /** What the OFFER carries: the seal's origin, final target and the seal bytes. */
    val offer: TransferOffer

    /** Chunk [index]'s ciphertext, or null when the source can no longer produce it. */
    suspend fun chunk(index: Int): ByteArray?

    override fun close() {}
}

/**
 * Our own seal: plaintext from the orchestrator (block-checked at the source), encrypted under the
 * seal key with the chunk's byte range as AAD. One verified stream per transfer, reused while
 * chunks are asked in order; a seek (restart, re-queue) re-opens it at that offset.
 */
internal class SealChunkSource(
    localDevice: PeerId,
    private val seal: OutgoingSeal,
    private val shape: TransferShape,
    private val plaintext: FilePlaintextSource,
    private val protection: FileProtection,
) : ChunkSource {
    override val offer = TransferOffer(localDevice, seal.targetDevice, seal.sealBytes)

    private var stream: PlaintextStream? = null
    private var streamPos: Long = -1

    override suspend fun chunk(index: Int): ByteArray? {
        val offset = shape.chunkOffset(index)
        val bytes = read(offset, shape.chunkLength(index)) ?: return null
        if (offset + bytes.size >= shape.byteTo) close()
        return protection.encryptChunk(seal.sealId, seal.key, offset, bytes)
    }

    private suspend fun read(offset: Long, length: Int): ByteArray? {
        var s = stream
        if (s == null || streamPos != offset) {
            close()
            s = plaintext.open(seal.fileId, offset) ?: return null
            stream = s
            streamPos = offset
        }
        val out = ByteArray(length)
        var got = 0
        while (got < length) {
            when (val read = s.read(length - got)) {
                is PlaintextRead.Bytes -> {
                    read.bytes.copyInto(out, got)
                    got += read.bytes.size
                    streamPos += read.bytes.size
                }

                // The source shrank or failed its block check: this source is done.
                PlaintextRead.Eof, is PlaintextRead.SourceLost -> {
                    close()
                    return null
                }
            }
        }
        return out
    }

    override fun close() {
        stream?.close()
        stream = null
        streamPos = -1
    }
}

/** A relay forward: the ciphertext the origin deposited, as it arrived. */
internal class RelayChunkSource(
    private val relay: RelayTransfer,
    private val store: FileRelayStore,
) : ChunkSource {
    override val offer = TransferOffer(relay.origin, relay.finalTarget, relay.sealBytes)

    override suspend fun chunk(index: Int): ByteArray? = store.chunk(relay.transferId, index)
}
