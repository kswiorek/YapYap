package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.CoroutineScope

/**
 * Standing counterpart to the ephemeral newcomer-side [OnboardingProvider]: serves OTHER nodes'
 * account-recovery requests (RECOVERY_REQUEST bootstrap packets). Runs on every node in every mode —
 * an always-on relay is exactly the bootstrap endpoint a recovering device points at — and never
 * "completes": a recovery request can arrive long after this node's own onboarding finished.
 *
 * Refuse-while-own-onboarding policy and the relay flow are implementation details; this interface
 * exists so wiring stays stable.
 */
interface RecoveryResponder {
    fun start(scope: CoroutineScope)

    suspend fun stop()
}