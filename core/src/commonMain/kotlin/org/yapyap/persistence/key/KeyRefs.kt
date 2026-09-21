package org.yapyap.persistence.key

import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.LOCAL_ACCOUNT_KEY_PREFIX
import org.yapyap.crypto.identity.LOCAL_DEVICE_KEY_PREFIX
import org.yapyap.crypto.identity.MASTER_KEY_ID

/**
 * Canonical [KeyReference] constructors for the `yapyap:` key namespace.
 * Single definition point — [org.yapyap.orchestrator.boot.BootDiagnoser],
 * [DefaultKeyStore.deleteAll] and the SPK/OPK repositories all build refs here
 * instead of concatenating key IDs by hand.
 */
fun localDeviceKeyRef(purpose: IdentityKeyPurpose, type: KeyType): KeyReference =
    KeyReference(
        keyId = LOCAL_DEVICE_KEY_PREFIX + purpose.name.lowercase(),
        purpose = purpose,
        type = type,
    )

fun localAccountKeyRef(purpose: IdentityKeyPurpose, type: KeyType): KeyReference =
    KeyReference(
        keyId = LOCAL_ACCOUNT_KEY_PREFIX + purpose.name.lowercase(),
        purpose = purpose,
        type = type,
    )

fun masterKeyRef(): KeyReference =
    KeyReference(
        keyId = MASTER_KEY_ID,
        purpose = IdentityKeyPurpose.ENCRYPTION,
        type = KeyType.PRIVATE,
    )

/** Local device signing private key — the identity BootDiagnoser probes. */
fun deviceSigningPrivateRef(): KeyReference =
    localDeviceKeyRef(IdentityKeyPurpose.SIGNING, KeyType.PRIVATE)

/** Local device encryption private key. */
fun deviceEncryptionPrivateRef(): KeyReference =
    localDeviceKeyRef(IdentityKeyPurpose.ENCRYPTION, KeyType.PRIVATE)

/** Local account signing private key. */
fun accountSigningPrivateRef(): KeyReference =
    localAccountKeyRef(IdentityKeyPurpose.SIGNING, KeyType.PRIVATE)

fun signedPreKeyPrivateRef(spkId: String): KeyReference =
    KeyReference(keyId = spkId, purpose = IdentityKeyPurpose.ENCRYPTION, type = KeyType.PRIVATE)

fun oneTimePreKeyPrivateRef(opkId: String): KeyReference =
    KeyReference(keyId = opkId, purpose = IdentityKeyPurpose.ENCRYPTION, type = KeyType.PRIVATE)

/**
 * Fixed refs deletable without DB enumeration (OS keyrings offer no listing).
 * Dynamic `spk-*`/`opk-*` IDs are enumerated from the DB at wipe time instead;
 * entries orphaned from their rows (crash between `putKey` and `insert`, or a
 * previous partial wipe) linger — harmless, as fresh provisioning mints new IDs
 * and the master-key wipe cryptographically retires the old DB.
 */
fun wellKnownKeyRefs(): List<KeyReference> = listOf(
    masterKeyRef(),
    localDeviceKeyRef(IdentityKeyPurpose.SIGNING, KeyType.PRIVATE),
    localDeviceKeyRef(IdentityKeyPurpose.SIGNING, KeyType.PUBLIC),
    localDeviceKeyRef(IdentityKeyPurpose.ENCRYPTION, KeyType.PRIVATE),
    localDeviceKeyRef(IdentityKeyPurpose.ENCRYPTION, KeyType.PUBLIC),
    localAccountKeyRef(IdentityKeyPurpose.SIGNING, KeyType.PRIVATE),
    localAccountKeyRef(IdentityKeyPurpose.SIGNING, KeyType.PUBLIC),
)
