package org.yapyap.routing.inbound.handlers

import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.db.AccountStatus
import org.yapyap.persistence.key.BootstrapSessionStore
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.envelopes.*
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.BootstrapPacketEvent
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Handles bootstrap-family packets ([org.yapyap.protocol.packet.PacketType.BOOTSTRAP]):
 * authenticate by kind (INTRO via the AEAD gate, RECOVERY_REQUEST via the account-key
 * signature), then forward to [bootstrapPackets]; the inbound processor ACKs on
 * [InboundHandleResult.Success].
 *
 * Policy refusals answer pre-emit with an honest NACK (no orchestrator callback — both are
 * persistence lookups): RECOVERY_REQUEST while this node is itself onboarding → DECLINED
 * (its projection is untrustworthy); account present-but-not-ACTIVE → DECLINED (banned must
 * not re-enter); account absent → [InboundHandleResult.Deferred] (absence asserts nothing —
 * the Add event may sit in a gap — and deferring clears dedup so the retry re-runs the check).
 * INTROs carry no check (the gate already proved onboarding); post-emit failures stay
 * invisible to the ACK by design, covered by the newcomer's session deadline (TIMED_OUT).
 */
internal class BootstrapInboundHandler(
    private val ctx: RoutingContext,
    private val bootstrapPackets: MutableSharedFlow<BootstrapPacketEvent>,
    private val sessionStore: BootstrapSessionStore,
    private val identityKeyRepository: IdentityKeyRepository,
) : InboundEnvelopeHandler {

    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val received = ctx.clock.now()
        val bootstrapEnvelope = runCatching { BootstrapEnvelope.decode(env.payload) }.getOrNull() ?: run {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode bootstrap envelope",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }

        if (bootstrapEnvelope.target != ctx.localDeviceId) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_WRONG_TARGET,
                message = "Bootstrap envelope received for peer ${bootstrapEnvelope.target}",
                fields = mapOf(
                    "sourceDeviceId" to bootstrapEnvelope.source,
                    "targetDeviceId" to bootstrapEnvelope.target,
                    "localDeviceId" to ctx.localDeviceId,
                ),
            )
            return InboundHandleResult.Rejected(PacketNackReason.WRONG_TARGET)
        }

        val payload = try {
            ctx.envelopeProtectionService.openBootstrap(bootstrapEnvelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open bootstrap envelope",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }

        if (bootstrapEnvelope.source != payload.device.deviceId) {
            // The envelope header source is AAD-bound, so a mismatch with the attested device id
            // means the packet was assembled inconsistently — reject.
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                message = "Bootstrap envelope source does not match attested device id",
                fields = mapOf(
                    "sourceDeviceId" to bootstrapEnvelope.source,
                    "attestedDeviceId" to payload.device.deviceId,
                ),
            )
            return InboundHandleResult.Rejected(PacketNackReason.PROTECTION_FAILED)
        }

        when (payload) {
            is Intro -> Unit // no policy gate: the AEAD open already proved an onboarding session
            is RecoveryRequest -> checkRecoveryPolicy(payload)?.let { return it }
            else -> {
                // INVITE never travels on the wire; BootstrapEnvelope.init rejects it, so this is
                // unreachable — fail closed.
                AppLog.error(
                    component = LogComponent.ROUTER,
                    event = LogEvent.ENVELOPE_UNKNOWN_TYPE,
                    message = "Unexpected bootstrap payload kind on the wire",
                    fields = mapOf("kind" to payload.kind),
                )
                return InboundHandleResult.Rejected(PacketNackReason.UNSUPPORTED_TYPE)
            }
        }

        bootstrapPackets.emit(BootstrapPacketEvent(payload, receivedAt = received))
        return InboundHandleResult.Success()
    }

    /** Responder-side policy; null means emit, otherwise answer with the disposition. */
    private suspend fun checkRecoveryPolicy(request: RecoveryRequest): InboundHandleResult? {
        if (sessionStore.session() != null) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_HANDLE_FAILED,
                message = "Declined recovery request while own onboarding session is active",
                fields = mapOf("sourceDeviceId" to request.device.deviceId),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        return when (val status = identityKeyRepository.getAccountStatus(request.account.accountId)) {
            null -> {
                // Unknown — the fold may not have seen the Add event yet. Defer (clears dedup)
                // so the sender's retry re-runs this check instead of being swallowed.
                AppLog.info(
                    component = LogComponent.ROUTER,
                    event = LogEvent.ENVELOPE_HANDLE_FAILED,
                    message = "Deferred recovery request for unknown account",
                    fields = mapOf("accountId" to request.account.accountId),
                )
                InboundHandleResult.Deferred()
            }

            AccountStatus.ACTIVE -> null
            else -> {
                AppLog.info(
                    component = LogComponent.ROUTER,
                    event = LogEvent.ENVELOPE_HANDLE_FAILED,
                    message = "Declined recovery request for non-active account",
                    fields = mapOf(
                        "accountId" to request.account.accountId,
                        "status" to status,
                    ),
                )
                InboundHandleResult.Rejected(PacketNackReason.DECLINED)
            }
        }
    }
}