package org.yapyap.gui.copy

import org.yapyap.orchestrator.runtime.globalevent.GlobalEventRefusal
import org.yapyap.orchestrator.runtime.message.SendRefusal
import org.yapyap.orchestrator.runtime.onboarding.SponsorRefusal
import org.yapyap.orchestrator.runtime.room.CreateRoomRefusal
import org.yapyap.orchestrator.runtime.room.RoomEventRefusal

/**
 * Single refusal→copy mapper. The backend returns typed values, never copy
 * (06-yapyap-mapping.md §7). User copy lives here and only here.
 */
object RefusalText {
    fun send(r: SendRefusal, overBy: Long = 0): String = when (r) {
        SendRefusal.NotMember -> "You're no longer in this room."
        SendRefusal.TooLarge -> "That message is too long ($overBy bytes over)."
        SendRefusal.HistoryIncomplete -> "Still syncing — try again in a moment."
    }

    fun createRoom(r: CreateRoomRefusal): String = when (r) {
        CreateRoomRefusal.EMPTY_MEMBERS -> "Pick at least one person."
        CreateRoomRefusal.UNKNOWN_MEMBER -> "One of those accounts isn't synced yet."
    }

    fun roomEvent(r: RoomEventRefusal): String = when (r) {
        RoomEventRefusal.RoomNotFound -> "That room doesn't exist here anymore."
        RoomEventRefusal.NotAdmin -> "Only admins can do that."
        RoomEventRefusal.NotMember -> "Not an active member of this room."
        RoomEventRefusal.UnknownMember -> "That account isn't synced yet."
        RoomEventRefusal.InvalidSuccessor -> "Pick an active member as the new owner."
        RoomEventRefusal.OwnerIrrevocable -> "The owner can't be removed or demoted."
        RoomEventRefusal.NotReady -> "Still syncing. Try again shortly."
    }

    fun global(r: GlobalEventRefusal): String = when (r) {
        GlobalEventRefusal.NotAdmin -> "Only admins can do that."
        GlobalEventRefusal.NotReady -> "Still syncing. Try again shortly."
        GlobalEventRefusal.TargetNotFound -> "That account or device wasn't found."
        GlobalEventRefusal.NotOwnDevice -> "That device belongs to another account."
        GlobalEventRefusal.OnboardingActive -> "Onboarding is still in progress."
        GlobalEventRefusal.InvalidSuccessor -> "Pick an active member as the new owner."
        GlobalEventRefusal.OwnerIrrevocable -> "The owner can't be removed or demoted."
        GlobalEventRefusal.RecoveryKeyRequired ->
            "Enter your recovery key to remove the last device."

        GlobalEventRefusal.TargetNotEligible -> "That action doesn't apply right now."
        GlobalEventRefusal.SelfServiceRequired ->
            "Manage your own devices and account from Settings → Devices."
    }

    fun sponsor(r: SponsorRefusal): String = when (r) {
        SponsorRefusal.SponsorNotAdmin -> "Only admins can sponsor new accounts."
        SponsorRefusal.SponsorNotReady -> "Still syncing — try again shortly."
        is SponsorRefusal.MalformedInvite ->
            "That code isn't valid — ask for a fresh one."
    }

    const val STUB_TOAST = "Coming in a later build."
}
