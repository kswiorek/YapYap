package org.yapyap.gui.copy

import org.yapyap.orchestrator.boot.ResetReason

/**
 * ResetReason → user-facing copy. The backend returns typed values, never copy;
 * user copy lives here and only here (same rule as [RefusalText]).
 */
object ResetReasonText {
    fun title(reason: ResetReason): String = when (reason) {
        ResetReason.SELF_BANNED -> "You have been removed from the network"
        ResetReason.INCONSISTENT_STORAGE -> "Local data is inconsistent"
        ResetReason.ONBOARDING_EXPIRED -> "Setup didn't finish"
    }

    fun body(reason: ResetReason): String = when (reason) {
        ResetReason.SELF_BANNED ->
            "This device (or its account) was removed by a network admin. " +
                    "This copy of the data can no longer rejoin on its own."

        ResetReason.INCONSISTENT_STORAGE ->
            "Stored keys and database rows no longer line up, so the app can't " +
                    "prove who this device is."

        ResetReason.ONBOARDING_EXPIRED ->
            "Joining never completed and the one-time setup secret is gone, so " +
                    "this install can't resume where it stopped."
    }

    fun nextStep(reason: ResetReason): String = when (reason) {
        ResetReason.SELF_BANNED ->
            "Resetting wipes local data and returns to setup. You'll need a fresh " +
                    "invite from an admin to rejoin."

        ResetReason.INCONSISTENT_STORAGE ->
            "Resetting wipes local data and returns to setup. If you have a " +
                    "recovery key you can restore; otherwise you'll need a fresh invite."

        ResetReason.ONBOARDING_EXPIRED ->
            "Resetting wipes local data and returns to setup — start again with " +
                    "a fresh code."
    }
}
