package org.yapyap.orchestrator.globalevent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.dag.MessageDraft
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.GlobalEventPayload
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.routing.router.Router

/**
 * Committed fold diff of the global control room. Consumers: the onboarding provider
 * (own-device anchoring → COMPLETE), the pending-reverify hook, the sprint-4d firewall, UI.
 */
sealed interface IdentityStateChange {
    data class AccountAdded(val accountId: AccountId) : IdentityStateChange
    data class AccountRemoved(val accountId: AccountId) : IdentityStateChange
    data class AdminGranted(val accountId: AccountId) : IdentityStateChange
    data class AdminRevoked(val accountId: AccountId) : IdentityStateChange
    data class DeviceAdded(val accountId: AccountId, val deviceId: PeerId) : IdentityStateChange
    data class DeviceRemoved(val deviceId: PeerId) : IdentityStateChange
}

/**
 * Sole writer of chain-derived identity columns (`accounts` / `devices`).
 *
 * Two roles in one class:
 *  - **control-plane writer**: the `publish*` functions are the only way identity events enter
 *    the DAG. Each call appends to the global room (signed by the local device via
 *    [DagEngine.append]), folds + commits synchronously, then broadcasts to the room members.
 *    The sponsor service and the recovery responder are thin callers over these.
 *  - **projector**: re-folds the global room on every ingest trigger and on boot, verifies each
 *    event against shadow-state keys, flips PENDING → VERIFIED/REJECTED, and commits the merge.
 *
 * The projector never touches private keys — consent signatures ([Invite.accountKeySignature],
 * [RecoveryRequest.accountSignature], genesis) are computed by the callers that hold the
 * account key; the fold only verifies them against fold-state pub keys via [CryptoProvider]
 * primitives (never the live-table-backed `SignatureProvider`).
 */
interface GlobalEventProjector {
    val stateChanges: Flow<IdentityStateChange>

    fun start(scope: CoroutineScope)
    suspend fun stop()

    /**
     * Genesis (create-new-network): AddAccount (DAG root, `prevId == null`, admin by definition)
     * + AddDevice self-introduction (branch 3, authorized by [accountKeySignature]).
     * The global room must be empty — the DAG structure itself enforces the "only once".
     */
    suspend fun publishGenesisAccount(
        account: AccountIdentityRecord,
        device: DeviceIdentityRecord,
        deviceType: DeviceType,
        torEndpoint: TorEndpoint,
        accountKeySignature: ByteArray,
    )

    /**
     * New-account sponsorship (§8.1): AddAccount + AddDevice back-to-back with the same signer
     * (branch 2, authorized by the invite's `accountKeySignature`), plus GrantAdmin when
     * [grantAdmin] — fail-fast on the local `is_admin` BEFORE appending anything.
     */
    suspend fun publishSponsoredNewAccount(invite: Invite, grantAdmin: Boolean)

    /** Add-device to the sponsor's own account (branch 1 — the invite carries no account). */
    suspend fun publishOwnAccountDevice(invite: Invite)

    /** Recovery relay (§8.2 phase 2): AddDevice carrying the request's `accountSignature` as the
     *  `key_signature` (branch 3). */
    suspend fun publishRelayedDevice(request: RecoveryRequest)

    suspend fun publishGrantAdmin(targetAccountId: AccountId)
    suspend fun publishRemoveAdmin(targetAccountId: AccountId)
    suspend fun publishRemoveAccount(targetAccountId: AccountId)
    suspend fun publishRemoveDevice(targetDeviceId: PeerId)
}

internal class DefaultGlobalEventProjector(
    private val dagEngine: DagEngine,
    private val pipeline: InboundMessagePipeline,
    private val messageRepository: MessageRepository,
    private val identityKeyRepository: IdentityKeyRepository,
    private val roomRepository: RoomRepository,
    private val router: Router,
    private val cryptoProvider: CryptoProvider,
) : GlobalEventProjector {

    private val _stateChanges = MutableSharedFlow<IdentityStateChange>(extraBufferCapacity = 64)
    override val stateChanges: Flow<IdentityStateChange> = _stateChanges.asSharedFlow()

    private var collectJob: Job? = null
    private var scope: CoroutineScope? = null

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        // Boot fold: commits whatever global history is already stored (idempotent full re-fold).
        scope.launch { foldAndCommit("boot") }
        if (collectJob?.isActive == true) return
        // Local appends bypass the pipeline, so every trigger here is a remote insert.
        // At 10–20 users the global log is tiny: a full re-fold on every trigger is
        // correct and cheap (optimize with watermarks only if ever needed).
        collectJob = scope.launch {
            pipeline.ingestResults.collect { result ->
                if (result.payload.roomId == RoomId.GLOBAL) foldAndCommit("ingest")
            }
        }
    }

    override suspend fun stop() {
        collectJob?.cancel()
        collectJob = null
        scope = null
    }

    override suspend fun publishGenesisAccount(
        account: AccountIdentityRecord,
        device: DeviceIdentityRecord,
        deviceType: DeviceType,
        torEndpoint: TorEndpoint,
        accountKeySignature: ByteArray,
    ) {
        require(messageRepository.findRoomTail(RoomId.GLOBAL) == null) {
            "genesis requires an empty global room"
        }
        val accountKey = requireNotNull(account.key) { "genesis requires the account public key" }
        require(accountKeySignature.isNotEmpty()) { "genesis requires the account signature over the device binding" }
        publish(
            listOf(
                GlobalEventPayload.AddAccount(
                    accountId = account.accountId,
                    accountSigningPublicKey = accountKey.publicKey,
                    displayName = account.displayName,
                ),
                GlobalEventPayload.AddDevice(
                    accountId = account.accountId,
                    deviceId = device.deviceId,
                    signingPublicKey = device.signing.publicKey,
                    encryptionPublicKey = device.encryption.publicKey,
                    torEndpoint = torEndpoint,
                    deviceType = deviceType,
                    keySignature = accountKeySignature,
                ),
            ),
        )
    }

    override suspend fun publishSponsoredNewAccount(invite: Invite, grantAdmin: Boolean) {
        val account = requireNotNull(invite.account) {
            "sponsored new-account publish requires invite.account"
        }
        val accountKey = requireNotNull(account.key) {
            "sponsored new-account publish requires the account public key"
        }
        val keySignature = requireNotNull(invite.accountKeySignature) {
            "sponsored new-account publish requires invite.accountKeySignature"
        }
        // Fail fast: GrantAdmin is valid only if the sponsor is admin at this fold position.
        check(!grantAdmin || identityKeyRepository.isLocalAccountAdmin()) {
            "local account is not an admin"
        }
        val events = mutableListOf<GlobalEventPayload>(
            GlobalEventPayload.AddAccount(
                accountId = account.accountId,
                accountSigningPublicKey = accountKey.publicKey,
                displayName = account.displayName,
            ),
            GlobalEventPayload.AddDevice(
                accountId = account.accountId,
                deviceId = invite.device.deviceId,
                signingPublicKey = invite.device.signing.publicKey,
                encryptionPublicKey = invite.device.encryption.publicKey,
                torEndpoint = invite.torEndpoint,
                deviceType = invite.deviceType,
                keySignature = keySignature,
            ),
        )
        // GrantAdmin is a separate event, never a field of AddAccount (admin status is
        // derived from the log).
        if (grantAdmin) events += GlobalEventPayload.GrantAdmin(account.accountId)
        publish(events)
    }

    override suspend fun publishOwnAccountDevice(invite: Invite) {
        require(invite.account == null) { "own-account device publish takes an account-less invite" }
        val localAccount = identityKeyRepository.getLocalAccountRecord()
            ?: error("sponsor has no local account")
        publish(
            listOf(
                GlobalEventPayload.AddDevice(
                    accountId = localAccount.accountId,
                    deviceId = invite.device.deviceId,
                    signingPublicKey = invite.device.signing.publicKey,
                    encryptionPublicKey = invite.device.encryption.publicKey,
                    torEndpoint = invite.torEndpoint,
                    deviceType = invite.deviceType,
                    // Branch 1: the sponsor's own authorship is the authorization — a sibling
                    // device does not hold the account private key, so no keySignature exists.
                    keySignature = null,
                ),
            ),
        )
    }

    override suspend fun publishRelayedDevice(request: RecoveryRequest) {
        requireNotNull(request.account.key) { "relayed device publish requires the account public key" }
        require(request.accountSignature.isNotEmpty()) { "relayed device publish requires request.accountSignature" }
        publish(
            listOf(
                GlobalEventPayload.AddDevice(
                    accountId = request.account.accountId,
                    deviceId = request.device.deviceId,
                    signingPublicKey = request.device.signing.publicKey,
                    encryptionPublicKey = request.device.encryption.publicKey,
                    torEndpoint = request.torEndpoint,
                    deviceType = request.deviceType,
                    keySignature = request.accountSignature,
                ),
            ),
        )
    }

    override suspend fun publishGrantAdmin(targetAccountId: AccountId) {
        publish(listOf(GlobalEventPayload.GrantAdmin(targetAccountId)))
    }

    override suspend fun publishRemoveAdmin(targetAccountId: AccountId) {
        publish(listOf(GlobalEventPayload.RemoveAdmin(targetAccountId)))
    }

    override suspend fun publishRemoveAccount(targetAccountId: AccountId) {
        publish(listOf(GlobalEventPayload.RemoveAccount(targetAccountId)))
    }

    override suspend fun publishRemoveDevice(targetDeviceId: PeerId) {
        publish(listOf(GlobalEventPayload.RemoveDevice(targetDeviceId)))
    }

    /**
     * Shared writer path: append (DagEngine signs with the local device key and chains off the
     * room tail) → fold + commit synchronously (the intro's dagHead must already include the new
     * nodes) → broadcast via the standard message path (sync stays the fallback).
     */
    private suspend fun publish(events: List<GlobalEventPayload>) {
        val nodes = events.map { dagEngine.append(RoomId.GLOBAL, MessageDraft.GlobalEvent(it)) }
        foldAndCommit("publish")
        broadcast(nodes)
        AppLog.info(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.MESSAGE_APPENDED,
            message = "Global events published to the control DAG",
            fields = mapOf(
                "eventKinds" to events.map { it.kind },
                "nodeCount" to nodes.size,
            ),
        )
    }

    private suspend fun broadcast(nodes: List<MessagePayload>) {
        val members = roomRepository.membersOfRoom(RoomId.GLOBAL)
        coroutineScope {
            nodes.flatMap { node ->
                members.map { member -> async { router.sendMessage(member, node) } }
            }.awaitAll()
        }
        AppLog.debug(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.OUTBOX_MESSAGE_QUEUED,
            message = "Global events broadcast to room members",
            fields = mapOf("nodeCount" to nodes.size, "memberCount" to members.size),
        )
    }

    /**
     * Canonical fold + commit. Serializes all triggers (publish path, ingest collector, boot).
     */
    private suspend fun foldAndCommit(trigger: String): Unit =
        TODO(
            "global events fold ($trigger): read findAllInRoom(GLOBAL), filter " +
                    "!isOrphaned && verificationState != REJECTED, sort " +
                    "(lamportClock, createdAt, messageId); replay into shadow state with per-event " +
                    "author resolution (AddDevice self-introduction special case), authorSignature " +
                    "verification against shadow keys via cryptoProvider.verifyDetached, §3 " +
                    "authorization branches, and keySignature re-verification via " +
                    "AddDevice.bindingBytes(); flip PENDING → VERIFIED/REJECTED via " +
                    "updateVerificationState; commit as a merge preserving local-only fields " +
                    "(is_local_*, reliability, last-seen, push token, prekeys) plus the provisional " +
                    "clear + placeholder fix-up; maintain room_members(GLOBAL); diff against the " +
                    "previous commit and emit IdentityStateChange. " +
                    "Prerequisites: devices.status migration, commit-shaped IdentityKeyRepository " +
                    "methods (account upsert w/ admin+status, device upsert w/ binding+status+provisional, " +
                    "account tombstones), RoomRepository.removeMember, and the global-room PENDING " +
                    "ingest policy (§4 verificationPolicy).",
        )
}
