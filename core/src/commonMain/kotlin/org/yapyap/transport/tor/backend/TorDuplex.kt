package org.yapyap.transport.tor.backend

import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.TorEndpoint
import org.yapyap.transport.TransportException
import org.yapyap.transport.tor.TorIncomingFrame
import kotlin.coroutines.cancellation.CancellationException

/**
 * One open, bidirectional connection through Tor (or loopback): the raw byte channels of a
 * stream whose hello has already been exchanged. The backend only dials and accepts; the
 * record protocol on top ([TorStreamRecordType]) belongs to the transport.
 */
internal interface TorDuplex {
    val input: ByteReadChannel
    val output: ByteWriteChannel

    /** Idempotent; unblocks pending reads and writes. */
    fun close()
}

/** An accepted stream: [source] is the endpoint the dialer *claims* in its hello (unauthenticated). */
internal class TorInboundStream(
    val source: TorEndpoint,
    val duplex: TorDuplex,
)

/** [TorDuplex] over a ktor socket whose read channel may already be open (a socket hands it out once). */
internal class SocketTorDuplex(
    private val socket: Socket,
    override val input: ByteReadChannel = socket.openReadChannel(),
    override val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = false),
) : TorDuplex {
    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * Accept-side handling shared by every socket backend: reads the connection's first int and
 * either reads one frame and emits it to [frames] (one-shot), or reads the stream hello and
 * hands the open connection to [streams] (session). Closes the socket unless a stream was
 * handed off. A malformed connection surfaces as
 * [org.yapyap.transport.TransportException.TorException.TransportFrameError]; cancellation
 * (both hierarchies) is rethrown untouched so backend shutdown never becomes a child failure.
 */
internal suspend fun handleTorConnection(
    client: Socket,
    maxPayloadBytes: Int,
    frames: MutableSharedFlow<TorIncomingFrame>,
    streams: SendChannel<TorInboundStream>,
    backendName: String,
) {
    var handedOff = false
    try {
        val input = client.openReadChannel()
        val received = try {
            when (val magic = input.readInt()) {
                TOR_TRANSPORT_FRAME_MAGIC -> readTorTransportFrameBody(input, maxPayloadBytes)
                TOR_STREAM_MAGIC -> TorInboundStream(readTorStreamHelloBody(input), SocketTorDuplex(client, input))
                else -> throw IllegalArgumentException("Invalid Tor transport magic 0x${magic.toUInt().toString(16)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (error: Exception) {
            AppLog.warn(
                component = LogComponent.TOR_BACKEND,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to read inbound $backendName transport frame",
                fields = mapOf("error" to error.toString()),
            )
            throw TransportException.TorException.TransportFrameError(error.message ?: "Unknown error")
        }
        when (received) {
            is TorIncomingFrame -> frames.emit(received)
            is TorInboundStream -> {
                handedOff = streams.trySend(received).isSuccess
                if (!handedOff) {
                    AppLog.warn(
                        component = LogComponent.TOR_BACKEND,
                        event = LogEvent.SESSION_FAILED,
                        message = "Inbound $backendName stream dropped: nobody is taking streams",
                        fields = mapOf("source" to received.source.onionAddress),
                    )
                }
            }
        }
    } finally {
        if (!handedOff) runCatching { client.close() }
    }
}
