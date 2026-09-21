package org.yapyap.orchestrator.boot

/**
 * Boot-time storage diagnosis (healthy → init directly, setup → fresh boot,
 * reset → inconsistent or self-removed/banned; GUI blocks on the latter).
 */
sealed interface BootDiagnosis {
    data object Healthy : BootDiagnosis
    data object SetupRequired : BootDiagnosis
    data class ResetRequired(val reason: ResetReason, val details: String) : BootDiagnosis
}

enum class ResetReason {
    INCONSISTENT_STORAGE,
    SELF_BANNED,
}
