package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.routing.router.Router

/**
 * Standing recovery responder: relays authenticated [RecoveryRequest]s as chain AddDevices and
 * replies with an AEAD intro. Stateless — the request's secret protects the reply once and is
 * never persisted, so concurrent requests can't clobber each other. Policy (decline/defer)
 * already ran in the inbound handler; only admitted requests reach here.
 */
internal class DefaultRecoveryResponder(
    private val router: Router,
    private val identityKeyRepository: IdentityKeyRepository,
    private val messageRepository: MessageRepository,
    private val localDeviceType: DeviceType,
) : RecoveryResponder {

    private var collectJob: Job? = null

    override fun start(scope: CoroutineScope) {
        collectJob = scope.launch {
            router.bootstrapPackets.collect { event ->
                val request = event.payload as? RecoveryRequest ?: return@collect
                onRecoveryRequest(request)
            }
        }
    }

    override suspend fun stop() {
        collectJob?.cancel()
    }

    private suspend fun onRecoveryRequest(request: RecoveryRequest) {
        // Append before replying: the intro's dagHead must already include the requester's
        // AddDevice, so the newcomer syncs and finds itself in the chain.
        appendRecoveryAddDevice(request)

        val ownAccount = identityKeyRepository.getLocalAccountRecord()
            ?: error("responder has no local account")
        val ownDevice = identityKeyRepository.getLocalDeviceRecord()
            ?: error("responder has no local device")
        val torEndpoint = identityKeyRepository.resolveTorEndpointForDevice(ownDevice.deviceId)
            ?: error("responder has no tor endpoint")
        router.sendBootstrap(
            Intro(
                account = ownAccount,
                device = ownDevice,
                deviceType = localDeviceType,
                torEndpoint = torEndpoint,
                dagHeadLamport = messageRepository.maxLamportInRoom(RoomId.GLOBAL) ?: 0L,
            ),
            target = request.device.deviceId,
            targetEndpoint = request.torEndpoint,
            sharedSecret = request.sharedSecret,
        )
    }

    private suspend fun appendRecoveryAddDevice(request: RecoveryRequest): Unit =
        TODO("global events: append AddDevice bound to the requester's account with key_signature = request.accountSignature, fold immediately, via the control-room writer (§8.2 phase 2)")
}
