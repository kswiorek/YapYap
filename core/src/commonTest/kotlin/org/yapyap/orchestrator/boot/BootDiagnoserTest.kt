package org.yapyap.orchestrator.boot

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.db.AccountRole
import org.yapyap.persistence.key.*
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

class BootDiagnoserTest {

    private val t0 = epochSeconds(10_000L)

    private companion object {
        const val SPK_ID = "yapyap:spk-test"
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
            if (withSpkKey) keyStore.putKey(signedPreKeyPrivateRef(SPK_ID), spkPair.privateKey)
            repo.insertLocalAccount(
                AccountIdentityRecord(
                    accountId,
                    "test",
                    IdentityPublicKeyRecord("local", 0, IdentityKeyPurpose.SIGNING, accountSigning.publicKey),
                ),
                role = AccountRole.MEMBER,
                provisional = provisional,
            )
            repo.insertLocalDevice(
                accountId,
                DeviceIdentityRecord(
                    deviceId,
                    IdentityPublicKeyRecord("local", 0, IdentityKeyPurpose.SIGNING, deviceSigning.publicKey),
                    IdentityPublicKeyRecord("local", 0, IdentityKeyPurpose.ENCRYPTION, deviceEncryption.publicKey),
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
    }

    @Test
    fun fresh_noRowsNoKeys_returnsSetupRequired() = runTest {
        val fixture = Fixture()

        assertEquals(BootDiagnosis.SetupRequired, fixture.diagnoser.diagnose())
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
