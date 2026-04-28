package org.yapyap.backend.db

import kotlin.random.Random
import org.yapyap.backend.protocol.PacketId

class DefaultPacketIdAllocator(
    private val database: YapYapDatabase,
    private val random: Random = Random.Default,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) : PacketIdAllocator {

    private var localDeviceId: String? = null

    init {
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
    }

    override fun assignLocalDevice(deviceId: String) {
        localDeviceId = deviceId
    }

    override fun allocate(): PacketId {
        requireNotNull(localDeviceId) { "Local device ID must be assigned before allocating packet ID" }
        repeat(maxAttempts) {
            val candidate = PacketId.random(random)
            if (tryReserve(sourceDeviceId = localDeviceId!!, packetId = candidate, receivedAtEpochSeconds = -1)) {
                return candidate
            }
        }
        error("Failed to allocate unique PacketId after $maxAttempts")
    }

    private fun tryReserve(sourceDeviceId: String, packetId: PacketId, receivedAtEpochSeconds: Long): Boolean {
        val queries = database.allocQueries
        return queries.transactionWithResult {
            queries.tryReservePacketId(
                packet_id = packetId.toHex(),
                source_device_id = sourceDeviceId,
                received_at = receivedAtEpochSeconds,
            )
            queries.selectLastInsertChanges().executeAsOne() > 0
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 32
    }
}
