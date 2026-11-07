package org.yapyap.crypto.identity

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.EncryptionPublicKey
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.keys.SignedPreKeyId
import org.yapyap.crypto.keys.SigningPrivateKey
import org.yapyap.crypto.keys.SigningPublicKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.crypto.store.KeyStore
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import kotlin.time.Instant

/** This device's own identity: who we are. Readable by every layer. */
internal interface LocalIdentity {
    /** The local device row; throws when it is missing (boot diagnosis handles that state). */
    suspend fun getLocalDeviceIdentity(): DeviceIdentity

    /** The local account row; throws when it is missing. */
    suspend fun getLocalAccountIdentity(): AccountIdentity

    suspend fun getLocalDeviceId(): PeerId

    suspend fun getLocalAccountId(): AccountId
}

/**
 * The local identity rows. Written only during setup (identity provisioning) and by boot's rebind
 * repair; the fold keeps the chain-derived columns current through its own writer.
 */
internal interface LocalIdentityStore {
    suspend fun getLocalDeviceIdentity(): DeviceIdentity?

    suspend fun getLocalAccountIdentity(): AccountIdentity?

    suspend fun insertLocalDevice(accountId: AccountId, identity: DeviceIdentity, provisional: Boolean = true)

    suspend fun insertLocalAccount(
        identity: AccountIdentity,
        role: AccountRole = AccountRole.MEMBER,
        provisional: Boolean = true,
    )

    /** Stores one of our own prekeys as active (rotation does not yet retire the previous one). */
    suspend fun insertSignedPreKey(spk: SignedPreKey, createdAt: Instant)

    /** All signed-prekey ids registered for a device (keystore cleanup at wipe). */
    suspend fun getSignedPreKeyIds(deviceId: PeerId): List<SignedPreKeyId>

    /**
     * Repair for stores written before the fold rebound the local account on confirmation: when
     * the local device is chain-confirmed under an account other than the local one, makes that
     * account local. Returns the previous local account id when it changed, else null.
     */
    suspend fun rebindLocalAccountToLocalDevice(): AccountId?
}

internal class DefaultLocalIdentity(
    private val cryptoProvider: CryptoProvider,
    private val store: LocalIdentityStore,
    private val privateKeyStore: KeyStore,
) : LocalIdentity {

    /** Public half from the keystore, or derived from the private half for stores written before it was kept. */
    private suspend fun localSigningPublicKey(
        privateSlot: KeySlot,
        publicSlot: KeySlot,
        onMissing: () -> CryptoException,
    ): SigningPublicKey {
        privateKeyStore.getKey(publicSlot)?.let { return SigningPublicKey(it) }
        val private = SigningPrivateKey(privateKeyStore.getKey(privateSlot) ?: throw onMissing())
        return cryptoProvider.privateSigningKeyToPublicKey(private)
    }

    private suspend fun buildLocalDeviceIdentityFromKeys(): DeviceIdentity {
        val missing = { CryptoException.MissingDeviceRecord("local device") }
        val signingKey = localSigningPublicKey(KeySlot.DeviceSigningPrivate, KeySlot.DeviceSigningPublic, missing)
        val encryptionKey = privateKeyStore.getKey(KeySlot.DeviceEncryptionPublic)?.let { EncryptionPublicKey(it) }
            ?: cryptoProvider.privateEncryptionKeyToPublicKey(
                EncryptionPrivateKey(privateKeyStore.getKey(KeySlot.DeviceEncryptionPrivate) ?: throw missing()),
            )
        return DeviceIdentity(
            deviceId = cryptoProvider.peerIdFromPublicKey(signingKey),
            signingKey = signingKey,
            encryptionKey = encryptionKey,
        )
    }

    private suspend fun buildLocalAccountIdentityFromKeys(): AccountIdentity {
        val signingKey = localSigningPublicKey(KeySlot.AccountSigningPrivate, KeySlot.AccountSigningPublic) {
            CryptoException.MissingAccountRecord("local account")
        }
        return AccountIdentity(
            accountId = cryptoProvider.accountIdFromPublicKey(signingKey),
            // DB row is authoritative when present; this is only used to re-insert a missing row.
            displayName = "",
            signingKey = signingKey,
        )
    }

    override suspend fun getLocalDeviceIdentity(): DeviceIdentity {
        val deviceRecord = store.getLocalDeviceIdentity()
        if (deviceRecord != null) {
            AppLog.info(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_FOUND,
                message = "Resolved local device identity record",
                fields = mapOf("deviceId" to deviceRecord.deviceId),
            )
            return deviceRecord
        }
        // Strict: a missing row is boot-diagnosis territory (BootDiagnoser ->
        // ResetRequired), never silently re-inserted here. Re-inserting masks an
        // intentional wipe and forks chain state (empty displayName, keyVersion 0,
        // no SPK/tor/admin history).
        val e = CryptoException.MissingDeviceRecord("local device")
        AppLog.error(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
            message = "Local device identity record missing",
            throwable = e,
        )
        throw e
    }

    override suspend fun getLocalAccountIdentity(): AccountIdentity {
        val accountRecord = store.getLocalAccountIdentity()


        if (accountRecord != null) {
            AppLog.info(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_ACCOUNT_RECORD_FOUND,
                message = "Resolved local account identity record",
                fields = mapOf("accountId" to accountRecord.accountId),
            )
            return accountRecord
        }

        // Strict: see getLocalDeviceIdentity above.
        val e = CryptoException.MissingAccountRecord("local account")
        AppLog.error(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_ACCOUNT_RECORD_MISSING,
            message = "Local account identity record missing",
            throwable = e,
        )
        throw e
    }

    override suspend fun getLocalDeviceId(): PeerId {
        return store.getLocalDeviceIdentity()?.deviceId
            ?: buildLocalDeviceIdentityFromKeys().deviceId
    }

    override suspend fun getLocalAccountId(): AccountId {
        return store.getLocalAccountIdentity()?.accountId
            ?: buildLocalAccountIdentityFromKeys().accountId
    }
}
