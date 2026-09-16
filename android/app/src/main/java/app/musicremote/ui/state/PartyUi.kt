package app.musicremote.ui.state

import app.musicremote.party.PartyQueue
import app.musicremote.party.QueueItem

// Party mode as the host UI sees it. Pure data plus pure functions, like HostUiState.

data class GuestUi(
    val id: String,
    val name: String,
    val joinedAt: Long,
)

data class PartyUi(
    val active: Boolean = false,
    /** Asked the relay to start a party; waiting for it to confirm. */
    val starting: Boolean = false,
    /** The link friends open, once the relay has created it. */
    val link: String? = null,
    val guests: List<GuestUi> = emptyList(),
    val current: QueueItem? = null,
    val upcoming: List<QueueItem> = emptyList(),
    val limitPerGuest: Int = PartyQueue.DEFAULT_LIMIT_PER_GUEST,
    val log: List<String> = emptyList(),
)

/** "Sam", "Sam +2", or "Host" for songs added in the app. */
fun requesterNames(item: QueueItem): String {
    val first = item.requestedBy.first()
    val others = item.requestedBy.size - 1
    return if (others > 0) "${first.name} +$others" else first.name
}

/** "5 guests · 3 in queue". */
fun partySummary(party: PartyUi): String {
    val guests = if (party.guests.size == 1) "1 guest" else "${party.guests.size} guests"
    val queued = when (party.upcoming.size) {
        0 -> "queue empty"
        else -> "${party.upcoming.size} in queue"
    }
    return "$guests · $queued"
}

/**
 * Who asked for the song on screen, if it's the queued song that's playing. Compared by
 * title because the session's metadata is what the screen shows.
 */
fun requestedByFor(party: PartyUi, nowPlayingTitle: String?): String? {
    val current = party.current ?: return null
    if (!party.active || nowPlayingTitle == null) return null
    val norm = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
    return if (norm(current.title) == norm(nowPlayingTitle)) requesterNames(current) else null
}

/** The message a friend receives with the link. */
fun partyShareText(link: String): String = "Join my Auxparty and add songs to the queue: $link"
