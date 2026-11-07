package org.yapyap.orchestrator.onboarding

import org.yapyap.crypto.identity.IdentityProvisioning
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.crypto.identity.LocalKeys
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.orchestrator.BootstrapEndpoint
import org.yapyap.orchestrator.SetupIntent
import org.yapyap.orchestrator.SetupResult
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.protocol.envelopes.accountSignedDeviceBindingBytes
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.routing.router.Router

/**
 * First-run setup, split around node assembly: [LocalSetupPreparer] provisions the local
 * identity before the node exists (the node's crypto stores need the local device), and
 * [LocalSetup] finishes the chosen path once the node runs (it needs the router, the
 * global projector and the onboarding provider).
 */
internal sealed interface PreparedSetup {
    val account: AccountIdentity
    val device: DeviceIdentity

    data class Genesis(
        override val account: AccountIdentity,
        override val device: DeviceIdentity,
        val recoveryKey: String,
    ) : PreparedSetup

    data class NewAccount(
        override val account: AccountIdentity,
        override val device: DeviceIdentity,
        val recoveryKey: String,
    ) : PreparedSetup

    data class Recovery(
        override val account: AccountIdentity,
        override val device: DeviceIdentity,
        val bootstrapEndpoint: BootstrapEndpoint,
    ) : PreparedSetup

    /** The account is a key-less stand-in until the fold confirms the device under its real one. */
    data class AddDevice(
        override val account: AccountIdentity,
        override val device: DeviceIdentity,
    ) : PreparedSetup
}

internal class LocalSetupPreparer(
    private val identityProvisioning: IdentityProvisioning,
) {
    suspend fun prepare(intent: SetupIntent): PreparedSetup = when (intent) {
        is SetupIntent.Genesis -> {
            val account = identityProvisioning.createNewAccountIdentity(intent.accountName, role = AccountRole.OWNER)
            val device = identityProvisioning.createNewDeviceIdentity()
            PreparedSetup.Genesis(account, device, identityProvisioning.exportLocalAccountRecoveryKey())
        }

        is SetupIntent.NewAccountFirstDevice -> {
            val account = identityProvisioning.createNewAccountIdentity(intent.accountName)
            val device = identityProvisioning.createNewDeviceIdentity()
            PreparedSetup.NewAccount(account, device, identityProvisioning.exportLocalAccountRecoveryKey())
        }

        is SetupIntent.ImportAccountRecoveryKey -> {
            val account = identityProvisioning.importLocalAccountFromRecovery(intent.recoveryKey)
            val device = identityProvisioning.createNewDeviceIdentity()
            PreparedSetup.Recovery(account, device, intent.bootstrapEndpoint)
        }

        //TODO: [Finishing Touches] if device is headless and belongs to an account, exclude from message fanount but not global room?
        SetupIntent.AddDeviceToExistingAccount -> {
            val account = identityProvisioning.createPlaceholderAccountIdentity()
            val device = identityProvisioning.createNewDeviceIdentity()
            PreparedSetup.AddDevice(account, device)
        }
    }
}

internal class LocalSetup(
    private val localIdentity: LocalIdentity,
    private val localKeys: LocalKeys,
    private val cryptoProvider: CryptoProvider,
    private val onboardingProvider: OnboardingProvider,
    private val globalEventProjector: GlobalEventProjector,
    private val router: Router,
    private val localDeviceType: DeviceType,
) {
    /**
     * Anchors the freshly provisioned account locally, before the node is reported as running:
     * stale onboarding secrets (e.g. wiped data dir, live keyring) are cleared, and the account
     * is a provisional GLOBAL member until the chain commits it.
     */
    suspend fun anchor(prepared: PreparedSetup) {
        onboardingProvider.cancelOnboarding()
        globalEventProjector.seedProvisionalMember(prepared.account.accountId)
    }

    /** Runs the path's network side and produces what the user needs to continue it. */
    suspend fun complete(prepared: PreparedSetup): SetupResult {
        val tor = router.localTorEndpoint()
        return when (prepared) {
            is PreparedSetup.Genesis -> {
                // Genesis: append AddAccount (DAG root, prevId == null — owner by definition, §3)
                // + AddDevice self-introduction, then fold immediately. The account key is online
                // here (fresh provisioning), so the binding signature is computed locally.
                globalEventProjector.publishGenesisAccount(
                    account = prepared.account,
                    device = prepared.device,
                    deviceType = localDeviceType,
                    torEndpoint = tor,
                    accountKeySignature = signDeviceBinding(prepared.account, prepared.device, tor),
                )
                // No sponsor invite — the network waits in limbo for its first newcomer.
                SetupResult(inviteBytes = null, recoveryKey = prepared.recoveryKey)
            }

            is PreparedSetup.NewAccount -> {
                // Join-existing-network path: always produces a sponsor invite (role seeded
                // MEMBER, projector-corrected). The provider persists the secret, enters
                // AWAITING_INTRO, and arms the timer. The newcomer's account key signs the device
                // binding — the sponsor relays it as the AddDevice key_signature (branch 2).
                val secret = cryptoProvider.randomBytes(32)
                onboardingProvider.beginSession(secret)
                val device = localIdentity.getLocalDeviceIdentity()
                SetupResult(
                    inviteBytes = Invite(
                        account = prepared.account,
                        device = device,
                        deviceType = localDeviceType,
                        torEndpoint = tor,
                        sharedSecret = secret,
                        accountKeySignature = signDeviceBinding(prepared.account, device, tor),
                    ).encode(),
                    recoveryKey = prepared.recoveryKey,
                )
            }

            is PreparedSetup.Recovery -> {
                // Recovery request (§8.2 phase 1): no known peers, so the request rides the outbox
                // with the user-supplied endpoint override. The account key signs the device
                // binding (possession proof, later the AddDevice key_signature); the secret
                // session-binds the responder's AEAD reply.
                val secret = cryptoProvider.randomBytes(32)
                onboardingProvider.beginSession(secret)
                val request = RecoveryRequest(
                    account = prepared.account,
                    device = prepared.device,
                    deviceType = localDeviceType,
                    torEndpoint = tor,
                    sharedSecret = secret,
                    accountSignature = signDeviceBinding(prepared.account, prepared.device, tor),
                )
                val requestId = router.sendBootstrap(
                    request,
                    target = prepared.bootstrapEndpoint.peerId,
                    targetEndpoint = prepared.bootstrapEndpoint.torEndpoint,
                )
                onboardingProvider.awaitRequestDelivery(requestId)
                // No sponsor QR — direct to the supplied bootstrap endpoint.
                SetupResult(inviteBytes = null, recoveryKey = null)
            }

            is PreparedSetup.AddDevice -> {
                val secret = cryptoProvider.randomBytes(32)
                onboardingProvider.beginSession(secret)
                SetupResult(
                    inviteBytes = Invite(
                        account = null, // existing account; the sponsor adds the device to its own
                        device = localIdentity.getLocalDeviceIdentity(),
                        deviceType = localDeviceType,
                        torEndpoint = tor,
                        sharedSecret = secret,
                        accountKeySignature = null, // branch 1: the sponsor's own authorship authorizes
                    ).encode(),
                    recoveryKey = null,
                )
            }
        }
    }

    /** The local account key's signature over [device]'s binding to [account]. */
    private suspend fun signDeviceBinding(
        account: AccountIdentity,
        device: DeviceIdentity,
        tor: TorEndpoint,
    ): ByteArray = cryptoProvider.signDetached(
        localKeys.localAccountSigningKey(),
        accountSignedDeviceBindingBytes(
            accountId = account.accountId,
            deviceId = device.deviceId,
            signingPublicKey = device.signingKey.bytes,
            encryptionPublicKey = device.encryptionKey.bytes,
            torEndpoint = tor,
            deviceType = localDeviceType,
        ),
    )
}
