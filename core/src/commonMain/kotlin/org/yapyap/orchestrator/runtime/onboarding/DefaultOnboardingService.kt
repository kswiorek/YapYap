package org.yapyap.orchestrator.runtime.onboarding

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.globalevent.GlobalEventProjector
import org.yapyap.orchestrator.onboarding.OnboardingProvider
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.persistence.db.DeviceType
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
 * broadcast — the service validates the invite shape, maps domain refusals to [SponsorOutcome],
 * and sends the intro. Generic throws are reserved for programming errors and infrastructure
 * failures; every user/data/state refusal is a value the GUI can display.
 */
internal class DefaultOnboardingService(
    private val provider: OnboardingProvider,
    private val router: Router,
    private val identityResolver: IdentityResolver,
    private val messageRepository: MessageRepository,
    private val cryptoProvider: CryptoProvider,
    private val localDeviceType: DeviceType,
    private val projector: GlobalEventProjector,
) : OnboardingService {

    override val newcomerState: StateFlow<OnboardingState> = provider.state

    override suspend fun newcomerCancelOnboarding() {
        provider.cancelOnboarding()
    }

    override suspend fun sponsorNewcomer(invite: Invite, admin: Boolean): SponsorOutcome {
        // 1. Shape: pure checks on the decoded invite, no I/O. A failure here means a corrupt
        //    QR / version skew — the GUI should ask for a fresh code, not crash.
        val newcomerAccount = invite.account?.let { account ->
            val key = account.key
                ?: return SponsorOutcome.Refused(
                    SponsorRefusal.MalformedInvite(InviteDefect.MISSING_ACCOUNT_KEY),
                )
            if (cryptoProvider.accountIdFromPublicKey(key.publicKey) != account.accountId) {
                return SponsorOutcome.Refused(
                    SponsorRefusal.MalformedInvite(InviteDefect.ACCOUNT_ID_MISMATCH),
                )
            }
            account
        }
        // 2. Permission: the admin toggle is meaningless on an existing-account invite — the
        //    invite is authoritative about the account, so proceed and report it via
        //    adminGranted = false. On a new account it requires a local admin; the bit can flip
        //    (revocation race) between UI render and scan, hence a refusal, not a throw.
        val grantAdmin = admin && newcomerAccount != null
        if (grantAdmin && !identityResolver.isLocalAccountAdmin()) {
            return SponsorOutcome.Refused(SponsorRefusal.SponsorNotAdmin)
        }
        // 3. Readiness, BEFORE any write: an empty global room means this device hasn't
        //    onboarded/synced yet — appending here would fork a parallel genesis.
        if (!messageRepository.hasMessages(RoomId.GLOBAL)) {
            return SponsorOutcome.Refused(SponsorRefusal.SponsorNotReady)
        }

        // Append before sending: the intro's dagHead must already include the newcomer's events.
        if (newcomerAccount == null) {
            projector.publishOwnAccountDevice(invite)
        } else {
            projector.publishSponsoredNewAccount(invite, admin)
        }

        val sponsorAccount = identityResolver.getLocalAccountIdentityRecord()
        val sponsorDevice = identityResolver.getLocalDeviceIdentityRecord()
        val torEndpoint = identityResolver.resolveTorEndpointForDevice(sponsorDevice.deviceId)
        router.sendBootstrap(
            Intro(
                account = sponsorAccount,
                device = sponsorDevice,
                deviceType = localDeviceType,
                torEndpoint = torEndpoint,
                // Re-read after publish: the head must include the newcomer's events. Non-null:
                // publish appended above, so an empty frontier here means storage lost the rows mid-call.
                dagHeadTipIds = messageRepository.findRoomFrontier(RoomId.GLOBAL)
                    .map { it.payload.messageId }
                    .ifEmpty { error("global room lost its head after publish") },
            ),
            target = invite.device.deviceId,
            targetEndpoint = invite.torEndpoint,
            sharedSecret = invite.sharedSecret,
        )
        return SponsorOutcome.Sponsored(adminGranted = grantAdmin)
    }
}
