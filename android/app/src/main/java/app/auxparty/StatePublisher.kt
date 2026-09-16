package app.auxparty

import kotlin.math.abs

/**
 * Decides which snapshots are worth sending to the relay.
 *
 * MediaBridge produces a snapshot every couple of seconds, but browsers
 * extrapolate position themselves, and every state message is a storage write
 * on the relay. So send only when something visible changed, or when the
 * position jumped away from where a browser would have extrapolated it (a seek).
 */
class StatePublisher(private val driftToleranceMs: Long = 2_500) {

    data class Decision(val state: Boolean, val artwork: Boolean)

    private var lastKey: String? = null
    private var lastTrack: String? = null
    private var lastPosition: Long? = null
    private var lastAt = 0L
    private var lastPlaying = false

    /** Forget everything, so the next snapshot is sent in full (e.g. after reconnecting). */
    fun reset() {
        lastKey = null
        lastTrack = null
        lastPosition = null
    }

    fun decide(s: NowPlaying, now: Long): Decision {
        val track = trackKey(s)
        val key = listOf(track, s.state, s.durationMs, s.volume, s.maxVolume).joinToString("|")

        // Where a browser that saw the last *sent* state thinks playback is now.
        val expected = lastPosition?.let { if (lastPlaying) it + (now - lastAt) else it }
        val position = s.positionMs
        val drifted = position != null && expected != null && abs(position - expected) > driftToleranceMs
        val positionAppeared = (position == null) != (lastPosition == null)

        val sendState = key != lastKey || drifted || positionAppeared
        val sendArtwork = track != lastTrack

        if (sendState) {
            lastKey = key
            lastPosition = position
            lastAt = now
            lastPlaying = s.state == "playing"
        }
        if (sendArtwork) lastTrack = track
        return Decision(sendState, sendArtwork)
    }

    companion object {
        fun trackKey(s: NowPlaying): String = listOf(s.packageName, s.title, s.artist, s.album).joinToString("|")
    }
}
