package org.yapyap.orchestrator.boot

import org.yapyap.orchestrator.NodeMode
import org.yapyap.protocol.DeviceType

// Frozen at boot, read from config file + platform factory
internal data class BootConfig(
    val mode: NodeMode = NodeMode.FULL_CLIENT,
    val localDeviceType: DeviceType = DeviceType.DESKTOP, // from platform factory, NOT a default
)
