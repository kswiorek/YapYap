package org.yapyap.crypto.keys

/**
 * A named place in the OS keyring. Every key the app holds outside the database has exactly one
 * slot; [keyringName] is the keyring account name and must never change for an existing slot
 * (installed devices look their keys up by it).
 */
internal sealed class KeySlot(val keyringName: String) {
    data object DeviceSigningPrivate : KeySlot("signing:yapyap:local_device:signing:private")
    data object DeviceSigningPublic : KeySlot("signing:yapyap:local_device:signing:public")
    data object DeviceEncryptionPrivate : KeySlot("encryption:yapyap:local_device:encryption:private")
    data object DeviceEncryptionPublic : KeySlot("encryption:yapyap:local_device:encryption:public")

    /** Held only on the account's first device (genesis, new account, recovery-key import). */
    data object AccountSigningPrivate : KeySlot("signing:yapyap:local_account:signing:private")

    /** Present exactly when the private half should be (first device). */
    data object AccountSigningPublic : KeySlot("signing:yapyap:local_account:signing:public")

    /** SQLCipher master key; never on the wire. */
    data object DatabaseMasterKey : KeySlot("encryption:yapyap:db-master-key:private")

    /** This node's own onboarding session (secret + deadline), see `BootstrapSessionStore`. */
    data object BootstrapSession : KeySlot("bootstrap_secret:yapyap:bootstrap:secret:private")

    data class SignedPreKeyPrivate(val id: SignedPreKeyId) : KeySlot("encryption:${id.value}:private")

    data class OneTimePreKeyPrivate(val id: OneTimePreKeyId) : KeySlot("encryption:${id.value}:private")

    // Getters, not stored lists: a stored list in the companion is built during KeySlot's class
    // init, which a nested object can trigger before its own INSTANCE is set (the list then holds null).
    companion object {
        /** The local device and account keys: everything well-known except the master key and bootstrap session. */
        val localIdentity: List<KeySlot>
            get() = listOf(
                DeviceSigningPrivate,
                DeviceSigningPublic,
                DeviceEncryptionPrivate,
                DeviceEncryptionPublic,
                AccountSigningPrivate,
                AccountSigningPublic,
            )

        /**
         * Slots deletable without DB enumeration (OS keyrings offer no listing). Prekey slots are
         * enumerated from the DB at wipe time instead; entries orphaned from their rows linger —
         * harmless, as fresh provisioning mints new ids and the master-key wipe retires the old DB.
         */
        val wellKnown: List<KeySlot>
            get() = listOf(DatabaseMasterKey) + localIdentity
    }
}

/** Key id prefixes; ids derive from the public key so they are stable and collision-free. */
internal const val SPK_ID_PREFIX: String = "yapyap:spk-"
internal const val OPK_ID_PREFIX: String = "yapyap:opk-"
