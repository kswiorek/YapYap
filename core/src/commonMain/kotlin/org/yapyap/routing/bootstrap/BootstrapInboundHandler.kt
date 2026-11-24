package org.yapyap.routing.bootstrap

import kotlinx.coroutines.flow.MutableSharedFlow
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapHeader
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.routing.inbound.InboundEnvelopeHandler
import org.yapyap.routing.inbound.inboundResultForProtectionFailure
import org.yapyap.routing.inbound.logInboundProtectionFailure
import org.yapyap.routing.router.BootstrapPacketEvent
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RoutingContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Handles bootstrap-family packets ([org.yapyap.protocol.PacketType.BOOTSTRAP]):
 * authenticate by kind (INTRO via the AEAD gate, RECOVERY_REQUEST via the account-key
 * signature), then forward to [bootstrapPackets]; the inbound processor ACKs on
 * [InboundHandleResult.Success].
 *
 * Policy refusals answer pre-emit with an honest NACK (no orchestrator callback — both are
 * reads through the routing context): RECOVERY_REQUEST while this node is itself onboarding → DECLINED
 * (its projection is untrustworthy); account present-but-not-ACTIVE → DECLINED (banned must
 * not re-enter); account absent → [InboundHandleResult.Deferred] (absence asserts nothing —
 * the Add event may sit in a gap — and deferring clears dedup so the retry re-runs the check).
 * INTROs carry no check (the gate already proved onboarding); post-emit failures stay
 * invisible to the ACK by design, covered by the newcomer's session deadline (TIMED_OUT).
 */
internal class BootstrapInboundHandler(
    private val ctx: RoutingContext,
    private val bootstrapPackets: MutableSharedFlow<BootstrapPacketEvent>,
) : InboundEnvelopeHandler {
    override suspend fun handle(env: BinaryEnvelope): InboundHandleResult {
        val received = ctx.clock.now()
        val header = runCatching { BootstrapHeader.decode(env.routingHeader) }.getOrNull() ?: run {
            AppLog.warn(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Failed to decode bootstrap header",
                fields = mapOf("error" to "decode_failed"),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECODE_FAILED)
        }

        val payload = try {
            ctx.envelopeProtections.bootstrap.open(header, env.content, source = env.source, target = env.target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            logInboundProtectionFailure(
                message = "Failed to open bootstrap content",
                packetId = env.packetId,
                source = env.source,
                exception = e,
            )
            return inboundResultForProtectionFailure(e)
        }

        if (env.source != payload.device.deviceId) {
            // The hop's source is bound into the AEAD AAD (INTRO), and a recovery request names the
            // device it signs for: a mismatch means the packet was assembled inconsistently.
            AppLog.error(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_PROTECTION_FAILED,
                message = "Bootstrap source does not match attested device id",
                fields = mapOf(
                    "sourceDeviceId" to env.source,
                    "attestedDeviceId" to payload.device.deviceId,
                ),
            )
            return InboundHandleResult.Rejected(PacketNackReason.PERMANENT_PROTECTION_FAILED)
        }

        when (payload) {
            is Intro -> Unit // no policy gate: the AEAD open already proved an onboarding session
            is RecoveryRequest -> checkRecoveryPolicy(payload)?.let { return it }
            else -> {
                // INVITE never travels on the wire; BootstrapProtection never protects one, so this is
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
        // Same read the AEAD intro gate uses (the armed onboarding session doubles as the
        // "this node is itself onboarding" signal); recovery requests skip that gate
        // (ACCOUNT_SIGNED), so the check runs here instead.
        if (ctx.envelopeProtections.bootstrap.hasActiveSession()) {
            AppLog.info(
                component = LogComponent.ROUTER,
                event = LogEvent.ENVELOPE_HANDLE_FAILED,
                message = "Declined recovery request while own onboarding session is active",
                fields = mapOf("sourceDeviceId" to request.device.deviceId),
            )
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        return when (val status = ctx.devices.getAccountStatus(request.account.accountId)) {
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

            IdentityStatus.ACTIVE -> null
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