package org.yapyap.orchestrator.runtime.onboarding


import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.protocol.envelopes.accountSignedDeviceBindingBytes
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Sponsor preview path ([decodeSponsorInvite], shared by `sponsorNewcomer` and
 * `inspectInvite`): decode + shape checks only, no writes. The service methods
 * themselves are thin `when` glue over this — intentionally untested here
 * (constructing the full service needs the router/projector fakes).
 */
class InspectInviteTest {

    private val crypto = DefaultCryptoProvider()

    private suspend fun sampleInvite(newAccount: Boolean): Invite {
        val signing = crypto.generateSigningKeyPair()
        val encryption = crypto.generateEncryptionKeyPair()
        val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
        val device = DeviceIdentity(
            deviceId = deviceId,
            signingKey = signing.publicKey,
            encryptionKey = encryption.publicKey,
        )
        val torEndpoint = TorEndpoint("newcomer.onion", 80)
        if (!newAccount) {
            return Invite(
                account = null,
                device = device,
                deviceType = DeviceType.DESKTOP,
                torEndpoint = torEndpoint,
                sharedSecret = byteArrayOf(0x01, 0x02, 0x03),
                accountKeySignature = null,
            )
        }
        val accountSigning = crypto.generateSigningKeyPair()
        val accountPublic = crypto.privateSigningKeyToPublicKey(accountSigning.privateKey)
        val accountId = crypto.accountIdFromPublicKey(accountPublic)
        val binding = accountSignedDeviceBindingBytes(
            accountId = accountId,
            deviceId = deviceId,
            signingPublicKey = signing.publicKey.bytes,
            encryptionPublicKey = encryption.publicKey.bytes,
            torEndpoint = torEndpoint,
            deviceType = DeviceType.DESKTOP,
        )
        return Invite(
            account = AccountIdentity(
                accountId = accountId,
                displayName = "Newcomer",
                signingKey = accountPublic,
            ),
            device = device,
            deviceType = DeviceType.DESKTOP,
            torEndpoint = torEndpoint,
            sharedSecret = byteArrayOf(0x01, 0x02, 0x03),
            accountKeySignature = crypto.signDetached(accountSigning.privateKey, binding),
        )
    }

    @Test
    fun newAccountInvite_decodesWithAccount() = runTest {
        val decoded = decodeSponsorInvite(sampleInvite(newAccount = true).encode(), crypto)
        assertIs<DecodedSponsorInvite.Ok>(decoded)
        val account = decoded.account
        assertIs<AccountIdentity>(account)
        assertEquals("Newcomer", account.displayName)
        assertEquals(decoded.invite.account?.accountId, account.accountId)
    }

    @Test
    fun addDeviceInvite_decodesWithoutAccount() = runTest {
        val decoded = decodeSponsorInvite(sampleInvite(newAccount = false).encode(), crypto)
        assertIs<DecodedSponsorInvite.Ok>(decoded)
        assertNull(decoded.account)
    }

    @Test
    fun garbageBytes_refusedAsMalformed() = runTest {
        val decoded = decodeSponsorInvite(byteArrayOf(0x01, 0x02, 0x03), crypto)
        assertIs<DecodedSponsorInvite.Refused>(decoded)
        assertEquals(
            SponsorRefusal.MalformedInvite(InviteDefect.MALFORMED_BYTES),
            decoded.reason,
        )
    }

    @Test
    fun accountWithoutKey_refusedAsMissingKey() = runTest {
        val valid = sampleInvite(newAccount = true)
        val keyless = valid.withAccount(valid.account!!.copy(signingKey = null))
        val decoded = decodeSponsorInvite(keyless.encode(), crypto)
        assertIs<DecodedSponsorInvite.Refused>(decoded)
        assertEquals(
            SponsorRefusal.MalformedInvite(InviteDefect.MISSING_ACCOUNT_KEY),
            decoded.reason,
        )
    }

    @Test
    fun tamperedAccountId_refusedAsMismatch() = runTest {
        val valid = sampleInvite(newAccount = true)
        val tampered = valid.withAccount(valid.account!!.copy(accountId = AccountId("tampered")))
        val decoded = decodeSponsorInvite(tampered.encode(), crypto)
        assertIs<DecodedSponsorInvite.Refused>(decoded)
        assertEquals(
            SponsorRefusal.MalformedInvite(InviteDefect.ACCOUNT_ID_MISMATCH),
            decoded.reason,
        )
    }

    private fun Invite.withAccount(account: AccountIdentity?): Invite = Invite(
        version = version,
        account = account,
        device = device,
        deviceType = deviceType,
        torEndpoint = torEndpoint,
        sharedSecret = sharedSecret,
        accountKeySignature = accountKeySignature,
    )
}
