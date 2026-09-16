package app.musicremote.ui.state

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import app.musicremote.ThemeMode

// Everything the host (phone) UI renders. Pure data plus pure functions, so the
// logic is unit-testable and every screen can be screenshot-tested from fakes.

enum class Connection { Connecting, Online, Offline }

data class NowPlayingUi(
    val title: String,
    val artist: String?,
    val album: String? = null,
    val source: String,
    val artwork: ImageBitmap?,
    val playing: Boolean,
    val positionMs: Long,
    /** SystemClock.elapsedRealtime() when [positionMs] was sampled. */
    val positionAtElapsed: Long = 0,
    val durationMs: Long?,
    val volume: Int,
    val maxVolume: Int,
)

data class LinkedBrowserUi(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long?,
)

data class PairCodeUi(
    val code: String,
    val issuedAt: Long,
    val expiresAt: Long,
)

data class SetupState(
    val notificationAccess: Boolean = false,
    val overlay: Boolean = false,
    val battery: Boolean = false,
    /** Null below Android 13, where no runtime permission exists. */
    val notifications: Boolean? = null,
    val ytmPackage: String? = null,
) {
    /** Notification access is the only hard requirement: without it nothing works. */
    val ready: Boolean get() = notificationAccess
    val allGranted: Boolean get() = notificationAccess && overlay && battery && notifications != false
}

data class HostUiState(
    val connection: Connection = Connection.Connecting,
    val connectionError: String? = null,
    val nowPlaying: NowPlayingUi? = null,
    /** Seed for the colour scheme, from the artwork. Null = brand colour. */
    val seed: Color? = null,
    val linked: List<LinkedBrowserUi> = emptyList(),
    val pairCode: PairCodeUi? = null,
    val inviteError: String? = null,
    /** Name of a browser that linked while the invite sheet was open. */
    val justLinked: String? = null,
    val setup: SetupState = SetupState(),
    val themeMode: ThemeMode = ThemeMode.System,
    val onboardingDone: Boolean = false,
    val party: PartyUi = PartyUi(),
    /** Why the last party action didn't go through, e.g. "offline". */
    val partyError: String? = null,
    val search: SearchUi = SearchUi(),
)

// ------------------------------------------------------------- onboarding

enum class SetupStep { Welcome, NotificationAccess, Overlay, Battery, Notifications, Done }

/** Steps that ask for something, in order. Notifications only exists on Android 13+. */
fun permissionSteps(setup: SetupState): List<SetupStep> = buildList {
    add(SetupStep.NotificationAccess)
    add(SetupStep.Overlay)
    add(SetupStep.Battery)
    if (setup.notifications != null) add(SetupStep.Notifications)
}

/**
 * The step to show: the first unmet requirement the user hasn't skipped.
 * Recomputed whenever the user returns from system settings, so granting a
 * permission advances automatically.
 */
fun currentStep(setup: SetupState, welcomeSeen: Boolean, skipped: Set<SetupStep>): SetupStep = when {
    !welcomeSeen -> SetupStep.Welcome
    !setup.notificationAccess -> SetupStep.NotificationAccess
    !setup.overlay && SetupStep.Overlay !in skipped -> SetupStep.Overlay
    !setup.battery && SetupStep.Battery !in skipped -> SetupStep.Battery
    setup.notifications == false && SetupStep.Notifications !in skipped -> SetupStep.Notifications
    else -> SetupStep.Done
}

// ------------------------------------------------------------ formatting

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** "just now", "5 min ago", "3 hr ago", "yesterday", "4 days ago", "12 weeks ago". */
fun formatRelative(then: Long, now: Long): String {
    val seconds = ((now - then) / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days == 1L -> "yesterday"
        days < 14 -> "$days days ago"
        else -> "${days / 7} weeks ago"
    }
}

/** Browsers present in [now] but not [before]: someone just used a pairing code. */
fun newlyLinked(before: List<LinkedBrowserUi>, now: List<LinkedBrowserUi>): List<LinkedBrowserUi> {
    val known = before.mapTo(HashSet()) { it.id }
    return now.filter { it.id !in known }
}

/** Formats a raw relay code for display: "K7QM3XPD" or "K7QM-3XPD" -> "K7QM-3XPD". */
fun displayCode(code: String): String {
    val raw = code.replace("-", "").uppercase()
    return if (raw.length == 8) "${raw.take(4)}-${raw.drop(4)}" else code
}
