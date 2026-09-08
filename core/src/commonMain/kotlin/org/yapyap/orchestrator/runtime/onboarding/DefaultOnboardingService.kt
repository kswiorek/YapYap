package org.yapyap.orchestrator.runtime.onboarding

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.globalevent.GlobalEventProjector
import org.yapyap.orchestrator.onboarding.OnboardingProvider
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.routing.router.Router

/**
 * Sponsor-side onboarding service (GUI-facing QR scan → sponsor flow). Stateless: the scanned
 * secret protects the intro once and is never persisted — one sponsor serves many newcomers
 * concurrently; the outbox is its only persistence. Newcomer state comes from the provider.
 *
 * Identity writes go through the [GlobalEventProjector]: append to the global DAG, fold, and
 * broadcast — the service only validates the invite shape and sends the intro.
 */
internal class DefaultOnboardingService(
    private val provider: OnboardingProvider,
    private val router: Router,
    private val identityKeyRepository: IdentityKeyRepository,
    private val messageRepository: MessageRepository,
    private val cryptoProvider: CryptoProvider,
    private val localDeviceType: DeviceType,
    private val projector: GlobalEventProjector,
) : OnboardingService {

    override val newcomerState: StateFlow<OnboardingState> = provider.state

    override suspend fun newcomerCancelOnboarding() {
        provider.cancelOnboarding()
    }

    override suspend fun sponsorNewcomer(invite: Invite, admin: Boolean) {
        require(invite.account != null || !admin) { "admin toggle applies only to new accounts" }
        val newcomerAccount = invite.account?.let { account ->
            val key = requireNotNull(account.key) { "new-account invite must carry the account public key" }
            require(cryptoProvider.accountIdFromPublicKey(key.publicKey) == account.accountId) {
                "invite account id does not match its public key"
            }
            account
        }
        // Fail fast: GrantAdmin is valid only if the sponsor is admin at this fold position.
        check(!admin || identityKeyRepository.isLocalAccountAdmin()) { "local account is not an admin" }

        // Append before sending: the intro's dagHead must already include the newcomer's events.
        if (newcomerAccount == null) {
            appendDeviceToOwnAccount(invite)
        } else {
            appendNewAccountWithDevice(invite, admin)
        }

        val sponsorAccount = identityKeyRepository.getLocalAccountRecord()
            ?: error("sponsor has no local account")
        val sponsorDevice = identityKeyRepository.getLocalDeviceRecord()
            ?: error("sponsor has no local device")
        val torEndpoint = identityKeyRepository.resolveTorEndpointForDevice(sponsorDevice.deviceId)
            ?: error("sponsor has no tor endpoint")
        router.sendBootstrap(
            Intro(
                account = sponsorAccount,
                device = sponsorDevice,
                deviceType = localDeviceType,
                torEndpoint = torEndpoint,
                dagHeadLamport = messageRepository.maxLamportInRoom(RoomId.GLOBAL) ?: 0L,
            ),
            target = invite.device.deviceId,
            targetEndpoint = invite.torEndpoint,
            sharedSecret = invite.sharedSecret,
        )
    }

    private suspend fun appendDeviceToOwnAccount(invite: Invite) {
        projector.publishOwnAccountDevice(invite)
    }

    private suspend fun appendNewAccountWithDevice(invite: Invite, admin: Boolean) {
        projector.publishSponsoredNewAccount(invite, admin)
    }
}
