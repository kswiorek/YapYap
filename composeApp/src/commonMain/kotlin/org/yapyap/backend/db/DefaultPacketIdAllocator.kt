package org.yapyap.backend.db

import kotlin.random.Random
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PeerId

class DefaultPacketIdAllocator(
    private val database: YapYapDatabase,
    private val random: Random = Random.Default,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val logger: AppLogger = NoopAppLogger,
) : PacketIdAllocator {

    private var localDeviceId: PeerId? = null

    init {
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
    }

    override fun assignLocalDevice(deviceId: PeerId) {
        localDeviceId = deviceId
        logger.info(
            component = LogComponent.DATABASE,
            event = LogEvent.PACKET_ALLOCATOR_DEVICE_ASSIGNED,
            message = "Assigned local device for packet ID allocation",
            fields = mapOf("deviceId" to deviceId),
        )
    }

    override fun allocate(): PacketId {
        requireNotNull(localDeviceId) { "Local device ID must be assigned before allocating packet ID" }
        repeat(maxAttempts) {
            val candidate = PacketId.random(random)
            if (tryReserve(sourceDeviceId = localDeviceId!!, packetId = candidate, receivedAtEpochSeconds = -1)) {
                logger.debug(
                    component = LogComponent.DATABASE,
                    event = LogEvent.PACKET_ID_ALLOCATED,
                    message = "Allocated packet ID",
                    fields = mapOf("packetId" to candidate.toHex(), "attempt" to it + 1),
                )
                return candidate
            }
        }
        logger.error(
            component = LogComponent.DATABASE,
            event = LogEvent.PACKET_ID_ALLOCATION_FAILED,
            message = "Failed to allocate unique packet ID",
            fields = mapOf("maxAttempts" to maxAttempts, "deviceId" to localDeviceId),
        )
        error("Failed to allocate unique PacketId after $maxAttempts")
    }

    private fun tryReserve(sourceDeviceId: PeerId, packetId: PacketId, receivedAtEpochSeconds: Long): Boolean {
        val queries = database.allocQueries
        return queries.transactionWithResult {
            queries.tryReservePacketId(
                packet_id = packetId.toHex(),
                source_device_id = sourceDeviceId.id,
                received_at = receivedAtEpochSeconds,
            )
            queries.selectLastInsertChanges().executeAsOne() > 0
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 32
    }
}
