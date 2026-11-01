package org.yapyap.orchestrator.boot

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.key.*
import org.yapyap.protocol.*
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

class BootDiagnoserTest {

    private val t0 = epochSeconds(10_000L)

    private companion object {
        const val SPK_ID = "yapyap:spk-test"
        val STAND_IN = AccountId("placeholder")
    }

    private class Fixture {
        val crypto = DefaultCryptoProvider()
        val keyStore = InMemoryKeyStore()
        val repo = InMemoryIdentityKeyRepository()
        val clock = FakeClock(epochSeconds(10_000L))
        val sessionStore = BootstrapSessionStore(keyStore)
        val diagnoser = BootDiagnoser(repo, keyStore, crypto, sessionStore, clock)

        suspend fun seedIdentity(provisional: Boolean, withSpkKey: Boolean = true) {
            val deviceSigning = crypto.generateSigningKeyPair()
            val deviceEncryption = crypto.generateEncryptionKeyPair()
            val accountSigning = crypto.generateSigningKeyPair()
            val deviceId = crypto.peerIdFromPublicKey(deviceSigning.publicKey)
            val accountId = crypto.accountIdFromPublicKey(accountSigning.publicKey)
            val spkPair = crypto.generateEncryptionKeyPair()
            keyStore.putKey(deviceSigningPrivateRef(), deviceSigning.privateKey)
            keyStore.putKey(deviceEncryptionPrivateRef(), deviceEncryption.privateKey)
            keyStore.putKey(accountSigningPrivateRef(), accountSigning.privateKey)
            keyStore.putKey(accountSigningPublicRef(), accountSigning.publicKey)
            if (withSpkKey) keyStore.putKey(signedPreKeyPrivateRef(SPK_ID), spkPair.privateKey)
            repo.insertLocalAccount(
                AccountIdentityRecord(
                    accountId,
                    "test",
                    IdentityPublicKeyRecord( IdentityKeyPurpose.SIGNING, accountSigning.publicKey),
                ),
                role = AccountRole.MEMBER,
                provisional = provisional,
            )
            repo.insertLocalDevice(
                accountId,
                DeviceIdentityRecord(
                    deviceId,
                    IdentityPublicKeyRecord( IdentityKeyPurpose.SIGNING, deviceSigning.publicKey),
                    IdentityPublicKeyRecord( IdentityKeyPurpose.ENCRYPTION, deviceEncryption.publicKey),
                    signedPreKey = SignedPreKeyRecord(
                        keyId = SPK_ID,
                        publicKey = spkPair.publicKey,
                        signature = crypto.signDetached(deviceSigning.privateKey, spkPair.publicKey),
                        privateKey = spkPair.privateKey,
                        deviceId = deviceId,
                        isActive = true,
                    ),
                ),
                provisional = provisional,
            )
        }

        /**
         * A device added to an existing account: device keys only, a key-less stand-in local
         * account, and the device row under that stand-in until the fold confirms it.
         */
        suspend fun seedKeylessDevice(): PeerId {
            val deviceSigning = crypto.generateSigningKeyPair()
            val deviceEncryption = crypto.generateEncryptionKeyPair()
            val deviceId = crypto.peerIdFromPublicKey(deviceSigning.publicKey)
            val spkPair = crypto.generateEncryptionKeyPair()
            keyStore.putKey(deviceSigningPrivateRef(), deviceSigning.privateKey)
            keyStore.putKey(deviceEncryptionPrivateRef(), deviceEncryption.privateKey)
            keyStore.putKey(signedPreKeyPrivateRef(SPK_ID), spkPair.privateKey)
            repo.insertLocalAccount(AccountIdentityRecord(STAND_IN, "placeholder"))
            repo.insertLocalDevice(
                STAND_IN,
                DeviceIdentityRecord(
                    deviceId,
                    IdentityPublicKeyRecord(IdentityKeyPurpose.SIGNING, deviceSigning.publicKey),
                    IdentityPublicKeyRecord(IdentityKeyPurpose.ENCRYPTION, deviceEncryption.publicKey),
                    signedPreKey = SignedPreKeyRecord(
                        keyId = SPK_ID,
                        publicKey = spkPair.publicKey,
                        signature = crypto.signDetached(deviceSigning.privateKey, spkPair.publicKey),
                        privateKey = spkPair.privateKey,
                        deviceId = deviceId,
                        isActive = true,
                    ),
                ),
                provisional = true,
            )
            return deviceId
        }

        /** The sponsor's AddDevice lands: the fold commits the real account, then our device under it. */
        suspend fun confirmUnder(deviceId: PeerId, accountId: AccountId) {
            val accountKey = crypto.generateSigningKeyPair().publicKey
            repo.upsertChainAccount(accountId, accountKey, AccountRole.OWNER, IdentityStatus.ACTIVE, "alice")
            val device = checkNotNull(repo.localDevice)
            repo.upsertChainDevice(
                deviceId = deviceId,
                accountId = accountId,
                deviceType = DeviceType.DESKTOP,
                torEndpoint = TorEndpoint("self.onion", 80),
                signingPublicKey = device.signing.publicKey,
                encryptionPublicKey = device.encryption.publicKey,
                status = IdentityStatus.ACTIVE,
            )
        }
    }

    private val realAccount = AccountId("a".repeat(64))

    @Test
    fun keylessDevice_midOnboarding_returnsHealthy() = runTest {
        val fixture = Fixture()
        fixture.seedKeylessDevice()
        fixture.sessionStore.setActiveSecret(ByteArray(32) { 7 }, t0 + 5.minutes)

        assertEquals(BootDiagnosis.Healthy, fixture.diagnoser.diagnose())
    }

    @Test
    fun keylessDevice_confirmed_returnsHealthy_onTheRealAccount() = runTest {
        val fixture = Fixture()
        val deviceId = fixture.seedKeylessDevice()
        fixture.confirmUnder(deviceId, realAccount)

        assertEquals(BootDiagnosis.Healthy, fixture.diagnoser.diagnose())
        assertEquals(realAccount, fixture.repo.getLocalAccountRecord()?.accountId)
    }

    @Test
    fun keylessDevice_storeFromBeforeTheRebind_isRepaired() = runTest {
        // Written by a build that never rebound: confirmed under the real account, yet the
        // local account is still the stand-in.
        val fixture = Fixture()
        val deviceId = fixture.seedKeylessDevice()
        val standIn = checkNotNull(fixture.repo.localAccount)
        fixture.confirmUnder(deviceId, realAccount)
        fixture.repo.localAccount = standIn
        fixture.repo.accounts[STAND_IN.id] = standIn

        assertEquals(BootDiagnosis.Healthy, fixture.diagnoser.diagnose())
        assertEquals(realAccount, fixture.repo.getLocalAccountRecord()?.accountId)
    }

    @Test
    fun keylessDevice_underAnotherAccountWhileProvisional_returnsInconsistentStorage() = runTest {
        val fixture = Fixture()
        val deviceId = fixture.seedKeylessDevice()
        fixture.sessionStore.setActiveSecret(ByteArray(32) { 7 }, t0 + 5.minutes)
        fixture.repo.insertLocalAccount(AccountIdentityRecord(realAccount, "someone"))

        val diagnosis = fixture.diagnoser.diagnose()
        assertIs<BootDiagnosis.ResetRequired>(diagnosis)
        assertEquals(ResetReason.INCONSISTENT_STORAGE, diagnosis.reason)
        assertEquals(STAND_IN, fixture.repo.getAccountIdForDevice(deviceId))
    }

    @Test
    fun fresh_noRowsNoKeys_returnsSetupRequired() = runTest {
        val fixture = Fixture()

        assertEquals(BootDiagnosis.SetupRequired, fixture.diagnoser.diagnose())
    }

    /** The data directory was deleted while the OS keyring kept the identity keys. */
    private suspend fun Fixture.leaveKeysWithoutRows() {
        val crypto = DefaultCryptoProvider()
        keyStore.putKey(masterKeyRef(), ByteArray(32) { 1 })
        keyStore.putKey(deviceSigningPrivateRef(), crypto.generateSigningKeyPair().privateKey)
        keyStore.putKey(deviceEncryptionPrivateRef(), crypto.generateEncryptionKeyPair().privateKey)
        val account = crypto.generateSigningKeyPair()
        keyStore.putKey(accountSigningPrivateRef(), account.privateKey)
        keyStore.putKey(accountSigningPublicRef(), account.publicKey)
    }

    @Test
    fun newStore_leftoverKeys_areClearedAndSetupRequired() = runTest {
        val fixture = Fixture()
        fixture.leaveKeysWithoutRows()

        assertEquals(BootDiagnosis.SetupRequired, fixture.diagnoser.diagnose(storeIsNew = true))
        for (ref in localIdentityKeyRefs()) assertNull(fixture.keyStore.getKey(ref), "${ref.keyId} cleared")
        assertNotNull(fixture.keyStore.getKey(masterKeyRef()), "the new store's master key stays")
    }

    @Test
    fun existingStore_keysWithoutRows_returnsInconsistentStorage() = runTest {
        // An existing store that lost its rows is not a leftover keyring: nothing is deleted.
        val fixture = Fixture()
        fixture.leaveKeysWithoutRows()

        val diagnosis = fixture.diagnoser.diagnose()
        assertIs<BootDiagnosis.ResetRequired>(diagnosis)
        assertEquals(ResetReason.INCONSISTENT_STORAGE, diagnosis.reason)
        assertNotNull(fixture.keyStore.getKey(deviceSigningPrivateRef()))
    }

    @Test
    fun newStore_noKeys_returnsSetupRequired() = runTest {
        assertEquals(BootDiagnosis.SetupRequired, Fixture().diagnoser.diagnose(storeIsNew = true))
    }

    @Test
    fun anchoredHealthy_returnsHealthy() = runTest {
        val fixture = Fixture()
        fixture.seedIdentity(provisional = false)

        assertEquals(BootDiagnosis.Healthy, fixture.diagnoser.diagnose())
    }

    @Test
    fun anchored_leftoverSession_returnsHealthy() = runTest {
        val fixture = Fixture()
        fixture.seedIdentity(provisional = false)
        fixture.sessionStore.setActiveSecret(ByteArray(32) { 7 }, t0 + 5.minutes)

        assertEquals(BootDiagnosis.Healthy, fixture.diagnoser.diagnose())
    }

    @Test
    fun provisional_liveSession_returnsHealthy() = runTest {
        // Mid-onboarding restart: the session can still complete, resume handles it.
        val fixture = Fixture()
        fixture.seedIdentity(provisional = true)
        fixture.sessionStore.setActiveSecret(ByteArray(32) { 7 }, t0 + 5.minutes)

        assertEquals(BootDiagnosis.Healthy, fixture.diagnoser.diagnose())
    }

    @Test
    fun provisional_expiredSession_returnsOnboardingExpired() = runTest {
        val fixture = Fixture()
        fixture.seedIdentity(provisional = true)
        fixture.sessionStore.setActiveSecret(ByteArray(32) { 7 }, t0 - 1.minutes)

        val diagnosis = fixture.diagnoser.diagnose()
        assertIs<BootDiagnosis.ResetRequired>(diagnosis)
        assertEquals(ResetReason.ONBOARDING_EXPIRED, diagnosis.reason)
    }

    @Test
    fun provisional_noSession_returnsOnboardingExpired() = runTest {
        // The zombie: secret burned by an earlier timeout, nothing can complete this.
        val fixture = Fixture()
        fixture.seedIdentity(provisional = true)

        val diagnosis = fixture.diagnoser.diagnose()
        assertIs<BootDiagnosis.ResetRequired>(diagnosis)
        assertEquals(ResetReason.ONBOARDING_EXPIRED, diagnosis.reason)
    }

    @Test
    fun partialRows_returnsInconsistentStorage() = runTest {
        val fixture = Fixture()
        fixture.seedIdentity(provisional = false)
        fixture.keyStore.deleteKey(accountSigningPrivateRef())

        val diagnosis = fixture.diagnoser.diagnose()
        assertIs<BootDiagnosis.ResetRequired>(diagnosis)
        assertEquals(ResetReason.INCONSISTENT_STORAGE, diagnosis.reason)
    }

    @Test
    fun provisional_missingSpkKey_returnsInconsistentStorage() = runTest {
        // Consistency reasons win over the onboarding check.
        val fixture = Fixture()
        fixture.seedIdentity(provisional = true, withSpkKey = false)
        fixture.sessionStore.setActiveSecret(ByteArray(32) { 7 }, t0 + 5.minutes)

        val diagnosis = fixture.diagnoser.diagnose()
        assertIs<BootDiagnosis.ResetRequired>(diagnosis)
        assertEquals(ResetReason.INCONSISTENT_STORAGE, diagnosis.reason)
    }
}
