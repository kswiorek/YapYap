package org.yapyap.backend.transport.tor

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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.time.Duration.Companion.milliseconds

/**
 * Managed desktop backend that launches a Tor process (Windows/Linux) and tunnels one framed payload per stream.
 */
class ManagedDesktopTorBackend(
    private val torExecutablePath: Path = defaultDesktopTorExecutablePath(),
    private val startupTimeoutMillis: Long = 120_000,
    private val deviceId: String = "default-device",
    private val torStateRootPath: Path = defaultTorStateRootPath(),
) : TorBackend {

    private val inboundFlow = MutableSharedFlow<TorIncomingFrame>(extraBufferCapacity = 64)
    override val incomingFrames: Flow<TorIncomingFrame> = inboundFlow.asSharedFlow()

    @Volatile
    private var socksPort: Int? = null

    @Volatile
    private var controlPort: Int? = null

    @Volatile
    private var bootstrapped = false

    @Volatile
    var publishedLocalEndpoint: TorEndpoint? = null
        private set

    private var localServicePort: Int? = null
    private var runtimeDir: Path? = null
    private var hiddenServiceDir: Path? = null
    private var torProcess: Process? = null
    private var acceptSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val recentLogs = ConcurrentLinkedDeque<String>()

    override suspend fun start(localPort: Int): TorEndpoint {
        check(torProcess == null) { "Tor backend already started" }
        require(Files.exists(torExecutablePath)) { "tor executable not found at $torExecutablePath" }
        recentLogs.clear()

        require(localPort in 1..65535) { "localPort must be in range 1..65535" }
        this.localServicePort = localPort
        val localRuntimeDir = resolveDeviceStateDirectory(deviceId, torStateRootPath)
        val localHiddenServiceDir = localRuntimeDir.resolve("hidden_service")
        Files.createDirectories(localHiddenServiceDir)
        runtimeDir = localRuntimeDir
        hiddenServiceDir = localHiddenServiceDir

        val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        listener.soTimeout = 1_000
        acceptSocket = listener

        val requestedSocksPort = pickEphemeralLocalPort()
        val requestedControlPort = pickEphemeralLocalPort()
        socksPort = requestedSocksPort
        controlPort = requestedControlPort

        val process = ProcessBuilder(
            listOf(
                torExecutablePath.toString(),
                "--SocksPort", "127.0.0.1:$requestedSocksPort",
                "--ControlPort", "127.0.0.1:$requestedControlPort",
                "--CookieAuthentication", "0",
                "--DataDirectory", localRuntimeDir.toString(),
                "--HiddenServiceDir", localHiddenServiceDir.toString(),
                "--HiddenServiceVersion", "3",
                "--HiddenServicePort", "$localPort 127.0.0.1:${listener.localPort}",
                "--Log", "notice stdout",
            )
        )
            .directory(localRuntimeDir.toFile())
            .redirectErrorStream(true)
            .start()
        torProcess = process

        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = localScope

        localScope.launch {
            process.inputStream.bufferedReader().forEachLine { line ->
                pushLog(line)
            }
        }

        try {
            waitUntilReady(localHiddenServiceDir)
            val hostnamePath = localHiddenServiceDir.resolve("hostname")
            val onionAddress = Files.readAllLines(hostnamePath).firstOrNull()?.trim()
                ?: error("Hidden service hostname file is empty")
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

        scope?.cancel()
        scope = null

        torProcess?.let { process ->
            process.destroy()
            runCatching { process.waitFor() }
            if (process.isAlive) {
                process.destroyForcibly()
                runCatching { process.waitFor() }
            }
        }
        torProcess = null

        runtimeDir = null
        hiddenServiceDir = null
        localServicePort = null
        publishedLocalEndpoint = null
        socksPort = null
        controlPort = null
        bootstrapped = false
        recentLogs.clear()
    }

    override suspend fun send(target: TorEndpoint, payload: ByteArray) {
        val localSource = publishedLocalEndpoint ?: error(
            "Tor backend must be started before send"
        )
        val localSocksPort = requireNotNull(socksPort) { "Tor socks port is not ready" }

        val deadlineMillis = System.currentTimeMillis() + 120_000
        while (true) {
            try {
                Socket("127.0.0.1", localSocksPort).use { socket ->
                    socket.soTimeout = 120_000
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                    performSocks5Handshake(target, input, output)
                    writeTransportFrame(output, localSource, payload)
                    output.flush()
                    return
                }
            } catch (error: SocksConnectException) {
                if (error.code != 4 || System.currentTimeMillis() >= deadlineMillis) {
                    throw IllegalArgumentException("SOCKS connect failed with code ${error.code}", error)
                }
                delay(1_000.milliseconds)
            }
        }
    }

    private suspend fun waitUntilReady(hiddenServicePath: Path) {
        val hostnamePath = hiddenServicePath.resolve("hostname")
        withTimeout(startupTimeoutMillis.milliseconds) {
            while (true) {
                refreshReadinessFromControlPort()
                val ready =
                    socksPort != null &&
                        bootstrapped &&
                        Files.exists(hostnamePath)

                if (ready) return@withTimeout

                val process = torProcess
                check(process != null && process.isAlive) {
                    "Tor process exited before becoming ready. Recent logs: ${recentLogs.joinToString(" | ")}"
                }
                delay(150.milliseconds)
            }
        }
    }

    private fun refreshReadinessFromControlPort() {
        val localControlPort = controlPort ?: return
        val snapshot = runCatching { queryControlSnapshot(localControlPort) }.getOrNull() ?: return
        socksPort = snapshot.socksPort ?: socksPort
        if (snapshot.bootstrapProgress >= 100) {
            bootstrapped = true
        }
    }

    private fun pushLog(line: String) {
        recentLogs.addLast(line)
        while (recentLogs.size > 30) {
            recentLogs.pollFirst()
        }
    }

    private fun acceptInboundConnections(listener: ServerSocket) {
        while (scope?.isActive == true) {
            val client = try {
                listener.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                break
            }

            scope?.launch {
                client.use { socket ->
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    val frame = runCatching { readTransportFrame(input) }.getOrNull() ?: return@launch
                    inboundFlow.emit(frame)
                }
            }
        }
    }

    private fun performSocks5Handshake(
        target: TorEndpoint,
        input: DataInputStream,
        output: DataOutputStream,
    ) {
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()

        val methodResponse = input.readNBytesStrict(2)
        require(methodResponse[0].toInt() == 0x05) { "Invalid SOCKS version in method response" }
        require(methodResponse[1].toInt() == 0x00) { "SOCKS server rejected no-auth method" }

        val hostBytes = target.onionAddress.encodeToByteArray()
        require(hostBytes.size <= 255) { "Target onion host is too long" }

        output.writeByte(0x05)
        output.writeByte(0x01)
        output.writeByte(0x00)
        output.writeByte(0x03)
        output.writeByte(hostBytes.size)
        output.write(hostBytes)
        output.writeByte((target.port ushr 8) and 0xff)
        output.writeByte(target.port and 0xff)
        output.flush()

        val header = input.readNBytesStrict(4)
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

        input.readNBytesStrict(addressLength + 2)
    }

    private fun queryControlSnapshot(port: Int): TorControlSnapshot {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            sendControlCommand(input, output, "AUTHENTICATE \"\"")
            val infoLines = sendControlCommand(input, output, "GETINFO status/bootstrap-phase net/listeners/socks")
            return parseControlSnapshot(infoLines)
        }
    }

    private fun sendControlCommand(
        input: BufferedReader,
        output: BufferedWriter,
        command: String,
    ): List<String> {
        output.write(command)
        output.write("\r\n")
        output.flush()

        val lines = mutableListOf<String>()
        while (true) {
            val line = input.readLine() ?: throw EOFException("Tor control connection closed")
            lines += line

            val code = line.take(3).toIntOrNull() ?: throw IllegalStateException("Invalid control response: $line")
            if (code >= 500) {
                throw IllegalStateException("Tor control command failed: ${lines.joinToString(" | ")}")
            }

            if (line.length >= 4 && line[3] == ' ') {
                return lines
            }
        }
    }

    private fun parseControlSnapshot(lines: List<String>): TorControlSnapshot {
        var bootstrapProgress = 0
        var socksListenerPort: Int? = null

        lines.forEach { line ->
            if (!line.startsWith("250-")) return@forEach
            val payload = line.substring(4)
            val separatorIndex = payload.indexOf('=')
            if (separatorIndex <= 0) return@forEach

            val key = payload.substring(0, separatorIndex)
            val value = payload.substring(separatorIndex + 1)

            when (key) {
                "status/bootstrap-phase" -> {
                    val progress = BOOTSTRAP_PROGRESS_REGEX.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (progress != null) bootstrapProgress = progress
                }
                "net/listeners/socks" -> {
                    socksListenerPort = SOCKS_LISTENER_PORT_REGEX
                        .findAll(value)
                        .lastOrNull()
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                }
            }
        }

        return TorControlSnapshot(
            bootstrapProgress = bootstrapProgress,
            socksPort = socksListenerPort,
        )
    }

    private fun writeTransportFrame(
        output: DataOutputStream,
        source: TorEndpoint,
        payload: ByteArray,
    ) {
        val sourceHost = source.onionAddress.encodeToByteArray()
        require(sourceHost.size <= 255) { "Source onion host is too long" }

        output.writeInt(FRAME_MAGIC)
        output.writeByte(sourceHost.size)
        output.write(sourceHost)
        output.writeShort(source.port)
        output.writeInt(payload.size)
        output.write(payload)
    }

    private fun readTransportFrame(input: DataInputStream): TorIncomingFrame {
        val magic = input.readInt()
        require(magic == FRAME_MAGIC) { "Invalid Tor transport frame magic" }

        val sourceHostLength = input.readUnsignedByte()
        val sourceHost = input.readNBytesStrict(sourceHostLength).decodeToString()
        val sourcePort = input.readUnsignedShort()
        val payloadLength = input.readInt()
        require(payloadLength >= 0) { "Payload length must be >= 0" }
        val payload = input.readNBytesStrict(payloadLength)

        return TorIncomingFrame(
            source = TorEndpoint(onionAddress = sourceHost, port = sourcePort),
            payload = payload,
        )
    }

    private fun DataInputStream.readNBytesStrict(count: Int): ByteArray {
        require(count >= 0) { "count must be >= 0" }
        val data = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(data, offset, count - offset)
            if (read < 0) throw EOFException("Unexpected EOF while reading $count bytes")
            offset += read
        }
        return data
    }

    private fun Closeable.safeClose() {
        runCatching { close() }
    }

    private class SocksConnectException(val code: Int) : RuntimeException()

    private data class TorControlSnapshot(
        val bootstrapProgress: Int,
        val socksPort: Int?,
    )

    private fun pickEphemeralLocalPort(): Int {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            return socket.localPort
        }
    }

    private fun resolveDeviceStateDirectory(deviceId: String, rootPath: Path): Path {
        val safeDeviceId = sanitizeDeviceId(deviceId)
        val statePath = rootPath.resolve(safeDeviceId)
        Files.createDirectories(statePath)
        return statePath
    }

    private fun sanitizeDeviceId(value: String): String {
        require(value.isNotBlank()) { "deviceId must not be blank" }
        return value.trim().replace(DEVICE_ID_SANITIZE_REGEX, "_")
    }

    companion object {
        private const val FRAME_MAGIC: Int = 0x59595431
        private val BOOTSTRAP_PROGRESS_REGEX = Regex("PROGRESS=(\\d+)")
        private val SOCKS_LISTENER_PORT_REGEX = Regex(":(\\d+)")
        private val DEVICE_ID_SANITIZE_REGEX = Regex("[^A-Za-z0-9._-]")

        fun defaultTorStateRootPath(): Path {
            return Paths.get(System.getProperty("user.home"), ".yapyap", "tor", "devices")
        }

        fun defaultWindowsTorExecutablePath(): Path {
            return findBundledTorBinaryPath("windows", "tor.exe")
        }

        fun defaultLinuxTorExecutablePath(): Path {
            return findBundledTorBinaryPath("linux", "tor")
        }

        fun anyBundledTorBinaryExists(): Boolean {
            return Files.exists(defaultWindowsTorExecutablePath()) || Files.exists(defaultLinuxTorExecutablePath())
        }

        fun defaultDesktopTorExecutablePath(): Path {
            val envOverride = System.getenv("YAPYAP_TOR_EXE")?.trim().orEmpty()
            if (envOverride.isNotEmpty()) {
                return Paths.get(envOverride)
            }

            val osName = System.getProperty("os.name").lowercase()
            return if (osName.contains("windows")) {
                defaultWindowsTorExecutablePath()
            } else {
                defaultLinuxTorExecutablePath()
            }
        }

        private fun findBundledTorBinaryPath(platformDir: String, executableName: String): Path {
            var current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
            repeat(6) {
                val candidate = current
                    .resolve("third_party")
                    .resolve("tor")
                    .resolve(platformDir)
                    .resolve(executableName)
                if (Files.exists(candidate)) {
                    return candidate
                }
                current = current.parent ?: return@repeat
            }

            return Paths.get(System.getProperty("user.dir"), "third_party", "tor", platformDir, executableName)
        }
    }
}










