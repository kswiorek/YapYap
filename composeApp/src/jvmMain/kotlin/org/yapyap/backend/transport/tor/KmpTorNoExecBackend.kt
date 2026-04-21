package org.yapyap.backend.transport.tor

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ASocket
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readShort
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import io.ktor.utils.io.writeShort
import io.matthewnelson.kmp.file.File
import io.matthewnelson.kmp.file.resolve
import io.matthewnelson.kmp.file.toFile
import io.matthewnelson.kmp.tor.resource.noexec.tor.ResourceLoaderTorNoExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import io.matthewnelson.kmp.tor.runtime.core.util.executeAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.yapyap.backend.protocol.TorEndpoint
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tor backend powered by kmp-tor runtime using noexec resources.
 */
class KmpTorNoExecBackend(
    private val startupTimeoutMillis: Long = 120_000,
    private val deviceId: String = "default-device",
    private val torStateRootPath: File = defaultTorStateRootPath(),
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
) : TorBackend {

    private val inboundFlow = MutableSharedFlow<TorIncomingFrame>(extraBufferCapacity = 64)
    override val incomingFrames: Flow<TorIncomingFrame> = inboundFlow.asSharedFlow()

    @Volatile
    private var socksPort: Int? = null

    @Volatile
    var publishedLocalEndpoint: TorEndpoint? = null
        private set

    private var localServicePort: Int? = null
    private var torRuntime: TorRuntime? = null
    private var acceptSocket: ServerSocket? = null
    private var selectorManager: SelectorManager? = null
    private var scope: CoroutineScope? = null

    override suspend fun start(localPort: Int): TorEndpoint {
        check(torRuntime == null) { "Tor backend already started" }
        require(localPort in 1..65535) { "localPort must be in range 1..65535" }
        this.localServicePort = localPort

        val localStateDir = resolveDeviceStateDirectory(deviceId, torStateRootPath)

        val selectorContext = if (coroutineContext == EmptyCoroutineContext) {
            Dispatchers.Default
        } else {
            coroutineContext
        }
        val selector = SelectorManager(selectorContext)
        selectorManager = selector

        val listener = aSocket(selector).tcp().bind("127.0.0.1", 0)
        acceptSocket = listener

        val runtime = createTorRuntime(localStateDir)
        torRuntime = runtime

        val localScope = CoroutineScope(SupervisorJob() + selectorContext)
        scope = localScope

        try {
            runtime.startDaemonAsync()
            waitUntilReady(runtime)

            val onionEntry = runtime.executeAsync(
                TorCmd.Onion.Add.new(ED25519_V3) {
                    port(localPort.toPort()) {
                        target(listener.port.toPort())
                    }
                }
            )
            val onionAddress = onionEntry.publicKey.address().toString().let { raw ->
                if (raw.endsWith(".onion", ignoreCase = true)) raw else "$raw.onion"
            }
            val resolvedEndpoint = TorEndpoint(onionAddress = onionAddress, port = localPort)
            publishedLocalEndpoint = resolvedEndpoint

            localScope.launch {
                acceptInboundConnections(listener)
            }
            return resolvedEndpoint
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    override suspend fun stop() {
        acceptSocket?.safeClose()
        acceptSocket = null

        selectorManager?.safeClose()
        selectorManager = null

        scope?.cancel()
        scope = null

        torRuntime?.let { runtime ->
            runCatching { runtime.stopDaemonAsync() }
        }
        torRuntime = null

        localServicePort = null
        publishedLocalEndpoint = null
        socksPort = null
    }

    override suspend fun send(target: TorEndpoint, payload: ByteArray) {
        val localSource = publishedLocalEndpoint ?: error("Tor backend must be started before send")
        val localSocksPort = requireNotNull(socksPort) { "Tor socks port is not ready" }
        val selector = requireNotNull(selectorManager) { "Tor selector manager is not initialized" }

        val deadlineMillis = System.currentTimeMillis() + 300_000
        while (true) {
            val socket = runCatching {
                aSocket(selector).tcp().connect("127.0.0.1", localSocksPort) {
                    socketTimeout = 180_000
                }
            }.getOrElse { throw it }

            try {
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = false)

                performSocks5Handshake(target, input, output)
                writeTransportFrame(output, localSource, payload)
                output.flush()
                return
            } catch (error: SocksConnectException) {
                if (error.code != 4 || System.currentTimeMillis() >= deadlineMillis) {
                    throw IllegalArgumentException("SOCKS connect failed with code ${error.code}", error)
                }
                delay(1_000.milliseconds)
            } finally {
                socket.safeClose()
            }
        }
    }

    private fun createTorRuntime(localStateDir: File): TorRuntime {
        val workDirectory = localStateDir.resolve("work")
        val cacheDirectory = localStateDir.resolve("cache")

        val environment = TorRuntime.Environment.Builder(
            workDirectory = workDirectory,
            cacheDirectory = cacheDirectory,
            loader = { resourceDir ->
                ResourceLoaderTorNoExec.getOrCreate(resourceDir)
            },
        )

        return TorRuntime.Builder(environment) {
            config { _ ->
                TorOption.__SocksPort.configure { auto() }
            }
        }
    }

    private suspend fun waitUntilReady(runtime: TorRuntime) {
        withTimeout(startupTimeoutMillis.milliseconds) {
            while (true) {
                socksPort = runtime.listeners().socks.lastOrNull()?.port?.value ?: socksPort
                val ready = socksPort != null && runtime.isReady()

                if (ready) return@withTimeout

                check(torRuntime != null) { "Tor runtime exited before becoming ready" }
                delay(150.milliseconds)
            }
        }
    }

    private suspend fun acceptInboundConnections(listener: ServerSocket) {
        while (scope?.isActive == true) {
            val client = runCatching { listener.accept() }.getOrElse { break }

            scope?.launch {
                try {
                    val input = client.openReadChannel()
                    val frame = runCatching { readTransportFrame(input) }.getOrNull() ?: return@launch
                    inboundFlow.emit(frame)
                } finally {
                    client.safeClose()
                }
            }
        }
    }

    private suspend fun performSocks5Handshake(
        target: TorEndpoint,
        input: ByteReadChannel,
        output: ByteWriteChannel,
    ) {
        output.writeByte(0x05)
        output.writeByte(0x01)
        output.writeByte(0x00)
        output.flush()

        val methodResponse = input.readByteArray(2)
        require(methodResponse[0].toInt() == 0x05) { "Invalid SOCKS version in method response" }
        require(methodResponse[1].toInt() == 0x00) { "SOCKS server rejected no-auth method" }

        val hostBytes = target.onionAddress.encodeToByteArray()
        require(hostBytes.size <= 255) { "Target onion host is too long" }

        output.writeByte(0x05)
        output.writeByte(0x01)
        output.writeByte(0x00)
        output.writeByte(0x03)
        output.writeByte(hostBytes.size.toByte())
        output.writeFully(hostBytes)
        output.writeByte(((target.port ushr 8) and 0xff).toByte())
        output.writeByte((target.port and 0xff).toByte())
        output.flush()

        val header = input.readByteArray(4)
        require(header[0].toInt() == 0x05) { "Invalid SOCKS version in connect response" }
        if (header[1].toInt() != 0x00) {
            throw SocksConnectException(header[1].toInt())
        }

        val addressLength = when (header[3].toInt()) {
            0x01 -> 4
            0x03 -> input.readUnsignedByte()
            0x04 -> 16
            else -> error("Unsupported SOCKS bind address type ${header[3].toInt()}")
        }

        input.readExact(addressLength + 2)
    }

    private suspend fun writeTransportFrame(
        output: ByteWriteChannel,
        source: TorEndpoint,
        payload: ByteArray,
    ) {
        val sourceHost = source.onionAddress.encodeToByteArray()
        require(sourceHost.size <= 255) { "Source onion host is too long" }

        output.writeInt(FRAME_MAGIC)
        output.writeByte(sourceHost.size.toByte())
        output.writeFully(sourceHost)
        output.writeShort(source.port.toShort())
        output.writeInt(payload.size)
        output.writeFully(payload)
    }

    private suspend fun readTransportFrame(input: ByteReadChannel): TorIncomingFrame {
        val magic = input.readInt()
        require(magic == FRAME_MAGIC) { "Invalid Tor transport frame magic" }

        val sourceHostLength = input.readUnsignedByte()
        val sourceHost = input.readExact(sourceHostLength).decodeToString()
        val sourcePort = input.readShort().toInt() and 0xffff
        val payloadLength = input.readInt()
        require(payloadLength >= 0) { "Payload length must be >= 0" }
        val payload = input.readExact(payloadLength)

        return TorIncomingFrame(
            source = TorEndpoint(onionAddress = sourceHost, port = sourcePort),
            payload = payload,
        )
    }

    private suspend fun ByteReadChannel.readUnsignedByte(): Int {
        return readByte().toInt() and 0xff
    }

    private suspend fun ByteReadChannel.readExact(count: Int): ByteArray {
        require(count >= 0) { "count must be >= 0" }
        val data = ByteArray(count)
        readFully(data, 0, count)
        return data
    }

    private fun ASocket.safeClose() {
        runCatching { close() }
    }

    private fun SelectorManager.safeClose() {
        runCatching { close() }
    }

    private class SocksConnectException(val code: Int) : RuntimeException()

    private fun resolveDeviceStateDirectory(deviceId: String, rootPath: File): File {
        val safeDeviceId = sanitizeDeviceId(deviceId)
        return rootPath.resolve(safeDeviceId)
    }

    private fun sanitizeDeviceId(value: String): String {
        require(value.isNotBlank()) { "deviceId must not be blank" }
        return value.trim().replace(DEVICE_ID_SANITIZE_REGEX, "_")
    }

    companion object {
        private const val FRAME_MAGIC: Int = 0x59595431
        private val DEVICE_ID_SANITIZE_REGEX = Regex("[^A-Za-z0-9._-]")

        fun defaultTorStateRootPath(): File {
            val userHome = System.getProperty("user.home").ifBlank { "." }
            return "$userHome/.yapyap/tor/devices".toFile()
        }
    }
}
