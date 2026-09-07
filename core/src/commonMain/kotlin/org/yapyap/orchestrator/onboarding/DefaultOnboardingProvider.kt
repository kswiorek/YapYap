package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.sync.SyncCoordinator
import org.yapyap.persistence.db.RoomMemberRole
import org.yapyap.persistence.key.BootstrapSessionStore
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.routing.router.Router
import org.yapyap.routing.router.RouterConfig
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Newcomer onboarding handshake: consumes authenticated [Intro]s, seeds the sponsor's
 * provisional rows, triggers the global-room range sync; the secret burns at COMPLETE
 * (still projector-owned — SYNCING is terminal until the fold reports our own Add event).
 *
 * Sole writer of the session slot ([BootstrapSessionStore]): all entry points funnel through
 * [beginSession] or the boot resume, so the timer is armed on every path. Dormant unless a
 * session exists. Every transition is logged (the headless onboarding UX, next to the
 * [OnboardingState] flow on the orchestrator).
 */
internal class DefaultOnboardingProvider(
    private val router: Router,
    private val sessionStore: BootstrapSessionStore,
    private val syncCoordinator: SyncCoordinator,
    private val identityKeyRepository: IdentityKeyRepository,
    private val roomRepository: RoomRepository,
    private val clock: Clock = Clock.System,
    private val routerConfig: StateFlow<RouterConfig>,
) : OnboardingProvider {

    private val _state = MutableStateFlow(OnboardingState.IDLE)
    override val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private var collectJob: Job? = null
    private var timeoutJob: Job? = null
    private var scope: CoroutineScope? = null

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        // Dormant unless resuming: no collector without a persisted session. Intros can't pass
        // the AEAD gate session-less, so nothing is lost; beginSession activates.
        scope.launch {
            if (sessionStore.session() == null) return@launch
            activate()
            resumeInterruptedOnboarding()
        }
    }

    /** Launch the intro collector (idempotent); [beginSession] calls this on the fresh-setup path. */
    private fun activate() {
        val owner = scope ?: return
        if (collectJob?.isActive == true) return
        // Subscribe before any intro can arrive; on a restart mid-onboarding an intro could
        // race this boot.
        collectJob = owner.launch {
            router.bootstrapPackets.collect { event ->
                val intro = event.payload as? Intro ?: return@collect
                onBootstrapIntro(intro)
            }
        }
    }

    override suspend fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
        collectJob?.cancel()
        collectJob = null
        scope = null
    }

    override suspend fun beginSession(secret: ByteArray) {
        checkNotNull(scope) { "OnboardingProvider must be started before beginning a session" }
        val deadline = clock.now() + routerConfig.value.bootstrapIntroLifetime
        sessionStore.setActiveSecret(secret, deadline)
        activate()
        transitionTo(
            OnboardingState.AWAITING_INTRO,
            "Onboarding session begun; waiting for sponsor intro",
            fields = mapOf("deadline" to deadline),
        )
        armTimeout(deadline)
    }

    override suspend fun cancelOnboarding() {
        timeoutJob?.cancel()
        timeoutJob = null
        sessionStore.burn()
        transitionTo(OnboardingState.IDLE, "onboarding session cleared; secret burned")
    }

    /** Single choke point for state changes — every transition is logged for headless operators. */
    private fun transitionTo(state: OnboardingState, message: String, fields: Map<String, Any?> = emptyMap()) {
        _state.value = state
        AppLog.info(
            component = LogComponent.ORCHESTRATOR,
            event = LogEvent.SESSION_STATE_CHANGED,
            message = message,
            fields = mapOf("onboardingState" to state) + fields,
        )
    }

    /**
     * A persisted session means a restart interrupted onboarding: re-enter AWAITING_INTRO,
     * unless the deadline passed (burn + TIMED_OUT) or the fold already anchors us
     * (burn + COMPLETE).
     */
    private suspend fun resumeInterruptedOnboarding() {
        val session = sessionStore.session() ?: return
        if (clock.now() >= session.deadline) {
            sessionStore.burn()
            transitionTo(
                OnboardingState.TIMED_OUT,
                "Onboarding session expired while down; secret burned",
                fields = mapOf("deadline" to session.deadline),
            )
        } else if (isLocalDeviceAnchoredInGlobalChain()) {
            sessionStore.burn()
            transitionTo(
                OnboardingState.COMPLETE,
                "Local device already anchored in the global chain; secret burned",
            )
        } else {
            transitionTo(
                OnboardingState.AWAITING_INTRO,
                "Resumed interrupted onboarding; waiting for sponsor intro",
                fields = mapOf("deadline" to session.deadline),
            )
            armTimeout(session.deadline)
        }
    }

    /**
     * Anchored once the projector's commit clears our device row's provisional bit — i.e. the
     * fold carries our own Add event. Until the projector exists every local row stays
     * provisional, so this reads false (same as the old hardcoded stub).
     *
     * TODO(global events projector): clear `devices.provisional` on commit (plus the
     * placeholder fix-up: correct the device's account_id to the chain account and drop the
     * placeholder account row).
     */
    private suspend fun isLocalDeviceAnchoredInGlobalChain(): Boolean {
        val localDeviceId = identityKeyRepository.getLocalDeviceRecord()?.deviceId ?: return false
        return !identityKeyRepository.isDeviceProvisional(localDeviceId)
    }

    /** Arm the in-memory expiry against the persisted deadline (re-armed on every transition). */
    private fun armTimeout(deadline: Instant) {
        val owner = scope ?: return
        timeoutJob?.cancel()
        timeoutJob = owner.launch {
            val remaining = deadline - clock.now()
            if (remaining > Duration.ZERO) delay(remaining)
            onTimeout()
        }
    }

    /** Burn the secret and surface TIMED_OUT once the persisted deadline passes. */
    private suspend fun onTimeout() {
        val current = _state.value
        if (current != OnboardingState.AWAITING_INTRO && current != OnboardingState.SYNCING) return
        val session = sessionStore.session() ?: return
        if (clock.now() < session.deadline) {
            // Stale timer (deadline was extended after this arm); re-arm against the new instant.
            armTimeout(session.deadline)
            return
        }
        sessionStore.burn()
        transitionTo(
            OnboardingState.TIMED_OUT,
            "Onboarding timed out waiting for intro/sync; secret burned",
            fields = mapOf("waitingIn" to current, "deadline" to session.deadline),
        )
    }

    private suspend fun onBootstrapIntro(intro: Intro) {
        // The sync phase gets its own budget — extend first, then do the fallible work.
        val syncDeadline = clock.now() + routerConfig.value.bootstrapIntroLifetime
        sessionStore.extendDeadline(syncDeadline)
        transitionTo(
            OnboardingState.SYNCING,
            "Sponsor intro received; seeding provisional rows and syncing global room",
            fields = mapOf(
                "sponsorDeviceId" to intro.device.deviceId,
                "sponsorAccountId" to intro.account.accountId,
                "dagHeadLamport" to intro.dagHeadLamport,
                "deadline" to syncDeadline,
            ),
        )
        armTimeout(syncDeadline)
        // FK order: account, device, membership. Never clobber existing rows with intro data
        // (admin stays projector-owned; both seeds are insert-only).
        identityKeyRepository.seedProvisionalPeerAccount(
            identity = intro.account,
            admin = false,
            displayName = intro.account.displayName,
        )
        identityKeyRepository.seedProvisionalPeerDevice(
            accountId = intro.account.accountId,
            deviceType = intro.deviceType,
            identity = intro.device,
            torEndpoint = intro.torEndpoint,
        )
        roomRepository.addMember(RoomId.GLOBAL, intro.account.accountId, RoomMemberRole.MEMBER)
        syncCoordinator.requestRangeSync(RoomId.GLOBAL, intro.dagHeadLamport)
        //TODO: COMPLETE + burn land with the global-events projector, once the fold carries the local
        // device's own Add event (isLocalDeviceAnchoredInGlobalChain). (flow)
    }
}
