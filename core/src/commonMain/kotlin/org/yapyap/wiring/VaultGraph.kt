package org.yapyap.wiring

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import org.yapyap.crypto.identity.DefaultIdentityProvisioning
import org.yapyap.crypto.identity.DefaultLocalIdentity
import org.yapyap.crypto.identity.DefaultLocalKeys
import org.yapyap.crypto.identity.DefaultPeerKeys
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.store.BootstrapSessionStore
import org.yapyap.crypto.store.DefaultKeyStore
import org.yapyap.crypto.store.DefaultMasterKeyProvider
import org.yapyap.crypto.store.DefaultOpkRepository
import org.yapyap.orchestrator.boot.BootConfig
import org.yapyap.orchestrator.boot.BootDiagnoser
import org.yapyap.orchestrator.boot.Node
import org.yapyap.orchestrator.boot.Vault
import org.yapyap.orchestrator.identity.DefaultIdentityStore
import org.yapyap.orchestrator.onboarding.LocalSetupPreparer
import org.yapyap.persistence.DatabaseFactory
import org.yapyap.persistence.YapYapDatabase
import kotlin.time.Clock

/**
 * Phase one of a node: the data directory's stores, usable before any local identity
 * exists (boot diagnosis and setup run here). Opened once per start; [assembleNode]
 * builds phase two on top of it.
 */
internal class VaultGraph private constructor(
    private val layout: DataLayout,
    private val bootConfig: BootConfig,
    private val platform: Platform,
    val configStore: ConfigStore,
    val keyStore: DefaultKeyStore,
    val cryptoProvider: DefaultCryptoProvider,
    val database: YapYapDatabase,
    private val driver: SqlDriver,
    override val storeIsNew: Boolean,
) : Vault {

    val identityStore = DefaultIdentityStore(database, bootConfig.localDeviceType)

    val localIdentity = DefaultLocalIdentity(
        cryptoProvider = cryptoProvider,
        store = identityStore,
        privateKeyStore = keyStore,
    )

    val localKeys = DefaultLocalKeys(
        localIdentity = localIdentity,
        directory = identityStore,
        privateKeyStore = keyStore,
    )

    val peerKeys = DefaultPeerKeys(cryptoProvider, identityStore)

    val bootstrapSessionStore = BootstrapSessionStore(keyStore)

    val identityProvisioning = DefaultIdentityProvisioning(
        cryptoProvider,
        identityStore,
        keyStore,
        localIdentity,
        localKeys,
        Clock.System,
    )

    // Owns the Healthy/SetupRequired/ResetRequired decision.
    override val bootDiagnoser =
        BootDiagnoser(
            identityStore,
            identityStore,
            keyStore,
            cryptoProvider,
            bootstrapSessionStore,
            Clock.System
        )

    override val setupPreparer = LocalSetupPreparer(identityProvisioning)

    override suspend fun assembleNode(scope: CoroutineScope): Node =
        NodeGraph(this, bootConfig, platform, layout, localIdentity.getLocalDeviceId(), scope)

    /**
     * Prekey slots the OS keyring cannot enumerate (`spk-*`/`opk-*` ids), read from the
     * database while it is still open, for the wipe.
     */
    suspend fun prekeySlots(): List<KeySlot> {
        val localDeviceId = identityStore.getLocalDeviceIdentity()?.deviceId ?: return emptyList()
        val opks = DefaultOpkRepository(database, keyStore, cryptoProvider, localDeviceId)
        return identityStore.getSignedPreKeyIds(localDeviceId).map { KeySlot.SignedPreKeyPrivate(it) } +
                opks.opkIds().map { KeySlot.OneTimePreKeyPrivate(it) }
    }

    override fun close() {
        driver.close()
    }

    companion object {
        suspend fun open(
            layout: DataLayout,
            bootConfig: BootConfig,
            platform: Platform,
            scope: CoroutineScope
        ): VaultGraph {
            // Loads userSettings.toml + the state.toml cache, then derives every layer's config.
            val configStore = ConfigStore(layout.userSettingsFile, layout.stateFile)
            configStore.start(platform.configFileWatcher(layout.userSettingsFile), scope)

            // TODO: [Sprint 7] fetch NetworkPolicy from clearnet API and call
            //   configStore.applyNetwork(fetched) before backends read the derived config.

            val keyStore = DefaultKeyStore(platform.keyringSessionFactory)
            val cryptoProvider = DefaultCryptoProvider()
            val masterKey = DefaultMasterKeyProvider(keyStore, cryptoProvider).getOrCreate()
            val connection = DatabaseFactory(platform.driverFactory(masterKey, layout.databaseFile)).createConnection()
            return VaultGraph(
                layout = layout,
                bootConfig = bootConfig,
                platform = platform,
                configStore = configStore,
                keyStore = keyStore,
                cryptoProvider = cryptoProvider,
                database = connection.database,
                driver = connection.driver,
                storeIsNew = connection.created,
            )
        }
    }
}
