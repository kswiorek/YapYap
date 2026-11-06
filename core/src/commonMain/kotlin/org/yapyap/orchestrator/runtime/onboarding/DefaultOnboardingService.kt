package org.yapyap.orchestrator.runtime.onboarding

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.orchestrator.dag.DagException
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.orchestrator.onboarding.OnboardingProvider
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.RoomId
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

    override suspend fun sponsorNewcomer(inviteBytes: ByteArray, admin: Boolean): SponsorOutcome {
        // Steps 0-1 (decode + shape) are shared with inspectInvite, so a
        // previewed invite sponsors exactly as previewed.
        val decoded = decodeSponsorInvite(inviteBytes, cryptoProvider)
        if (decoded is DecodedSponsorInvite.Refused) {
            return SponsorOutcome.Refused(decoded.reason)
        }
        decoded as DecodedSponsorInvite.Ok
        val invite = decoded.invite
        val newcomerAccount = decoded.account
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
        // A parked global frontier (open gaps at every tip) refuses the append — same outcome
        // as not ready: the sponsor's history is still syncing, so nothing is appended and
        // no intro goes out.
        try {
            if (newcomerAccount == null) {
                projector.publishOwnAccountDevice(invite)
            } else {
                projector.publishSponsoredNewAccount(invite, admin)
            }
        } catch (_: DagException.FrontierUnavailable) {
            return SponsorOutcome.Refused(SponsorRefusal.SponsorNotReady)
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

    override suspend fun inspectInvite(inviteBytes: ByteArray): InspectInviteResult {
        return when (val decoded = decodeSponsorInvite(inviteBytes, cryptoProvider)) {
            is DecodedSponsorInvite.Refused -> InspectInviteResult.Refused(decoded.reason)
            is DecodedSponsorInvite.Ok -> {
                val account = decoded.account
                if (account == null) {
                    InspectInviteResult.AddDevice(decoded.invite.deviceType)
                } else {
                    InspectInviteResult.NewAccount(account.displayName, account.accountId)
                }
            }
        }
    }
}

/**
 * Sponsor-invite decode + shape checks (the sponsor flow's steps 0-1), shared by
 * [DefaultOnboardingService.sponsorNewcomer] and
 * [DefaultOnboardingService.inspectInvite]. Pure: no I/O beyond the account-id
 * derivation, no writes, nothing consumed.
 */
internal sealed interface DecodedSponsorInvite {
    data class Refused(val reason: SponsorRefusal) : DecodedSponsorInvite
    data class Ok(val invite: Invite, val account: AccountIdentityRecord?) : DecodedSponsorInvite
}

internal suspend fun decodeSponsorInvite(
    inviteBytes: ByteArray,
    crypto: CryptoProvider,
): DecodedSponsorInvite {
    // 0. Decode: raw QR bytes into the invite. A failure here means a corrupt QR /
    //    version skew — the GUI should ask for a fresh code, not crash.
    val invite = try {
        Invite.decode(inviteBytes)
    } catch (_: RuntimeException) {
        return DecodedSponsorInvite.Refused(
            SponsorRefusal.MalformedInvite(InviteDefect.MALFORMED_BYTES),
        )
    }
    // 1. Shape: pure checks on the decoded invite, no I/O. A failure here means a corrupt
    //    QR / version skew — the GUI should ask for a fresh code, not crash.
    val newcomerAccount = invite.account?.let { account ->
        val key = account.key
            ?: return DecodedSponsorInvite.Refused(
                SponsorRefusal.MalformedInvite(InviteDefect.MISSING_ACCOUNT_KEY),
            )
        if (crypto.accountIdFromPublicKey(key.publicKey) != account.accountId) {
            return DecodedSponsorInvite.Refused(
                SponsorRefusal.MalformedInvite(InviteDefect.ACCOUNT_ID_MISMATCH),
            )
        }
        account
    }
    return DecodedSponsorInvite.Ok(invite, newcomerAccount)
}
