package app.auxparty.ui.state

import app.auxparty.SearchResult

/** Searching from the phone itself: picks play now (Remote) or join the queue (Party). */
data class SearchUi(
    val query: String = "",
    val searching: Boolean = false,
    /** Whether a search has finished at least once, to tell "no results" from "not searched". */
    val searched: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val error: String? = null,
    /** The result being started or added right now. */
    val busyVideoId: String? = null,
    /** One-off feedback for a snackbar, e.g. "Added “Numb” · #3 in line". */
    val message: String? = null,
)

/** "Elderbrook · Why Do We Shake In The Cold? · 3:51". */
fun resultSupporting(result: SearchResult): String =
    listOfNotNull(result.artist, result.album, result.duration).joinToString(" · ")

/** Feedback after the host adds a song to the party queue. */
fun queueAddMessage(title: String, position: Int, duplicate: Boolean): String = when {
    position == 0 -> if (duplicate) "“$title” is playing now" else "Playing “$title” now"
    duplicate -> "“$title” is already in the queue (#$position)"
    else -> "Added “$title” · #$position in line"
}

/** A sentence for a phone-side error code from playing or queueing a song. */
fun hostErrorMessage(code: String): String = when (code) {
    "overlay_permission_missing" -> "Allow Auxparty to display over other apps so it can start songs."
    "party_off" -> "The party has ended."
    "queue_full" -> "The queue is full."
    "bad_request" -> "That song can't be added."
    else -> code
}
