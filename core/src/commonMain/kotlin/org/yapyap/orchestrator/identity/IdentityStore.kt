package org.yapyap.orchestrator.identity

import org.yapyap.crypto.identity.*
import org.yapyap.orchestrator.identity.IdentityReader
import org.yapyap.orchestrator.identity.IdentityWriter
import org.yapyap.orchestrator.identity.ProvisionalIdentitySeeder
import org.yapyap.protocol.*
import org.yapyap.routing.directory.DeviceDirectory

/**
 * The whole `accounts`/`devices`/prekey store. Only the composition root holds this type; every
 * consumer receives the narrow interface it needs, so write access is decided at wiring time.
 */
internal interface IdentityStore :
    IdentityReader,
    IdentityWriter,
    ProvisionalIdentitySeeder,
    LocalIdentityStore,
    PeerKeyDirectory,
    DeviceDirectory {

    /** Non-provisional seeding of a peer device; tests use it to build rosters directly. */
    suspend fun insertPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentity,
        torEndpoint: TorEndpoint,
        provisional: Boolean = true
    )

    /** Seeding of a peer account; tests use it to build rosters directly. */
    suspend fun insertPeerAccount(
        identity: AccountIdentity,
        role: AccountRole,
        status: IdentityStatus,
        displayName: String,
        provisional: Boolean = true
    )

    suspend fun getAllDeviceIds(): List<PeerId>
}
