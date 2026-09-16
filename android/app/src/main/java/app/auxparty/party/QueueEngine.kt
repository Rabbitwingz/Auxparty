package app.auxparty.party

/**
 * Decides when the party queue should start its next song, from what the phone's
 * media session reports. It owns no timers and touches no Android APIs: callers feed
 * it observations (on every media change and whenever it asks to be checked again)
 * and act on the decision.
 *
 * Every track that plays is one of three kinds:
 * - **Requested**: a song from the queue, started by Auxparty. Plays to the end.
 * - **Host pick**: something the host chose mid-song (in YouTube Music or with
 *   "Play now"). Also plays to the end; the queue carries on afterwards.
 * - **Filler**: autoplay after a song ended, or whatever was already playing when
 *   the party started. A waiting request cuts in right away.
 *
 * YouTube Music has no queue API, so the next song is started just before the
 * current one ends. If that is missed (a late timer while the phone sleeps),
 * autoplay takes over, and that track counts as filler and is cut straight away.
 */
class QueueEngine(
    /** A track that changes within this long of the previous one's end ended naturally. */
    private val endWindowMs: Long = 4_000,
    /** How long before the end to start the next song, so there is no gap. */
    private val preemptMs: Long = 800,
) {
    enum class Kind { Requested, HostPick, Filler }

    data class Observation(
        val mediaId: String?,
        val title: String?,
        /** MediaBridge state names: playing, paused, stopped, buffering, none, … */
        val playback: String,
        /** Position as of [at]. */
        val positionMs: Long?,
        val durationMs: Long?,
        /** A monotonic clock, e.g. SystemClock.elapsedRealtime(). */
        val at: Long,
    ) {
        val track: String? get() = mediaId ?: title
    }

    sealed class Decision {
        /** Nothing to do; observe again after [checkInMs] (null: on the next media change). */
        data class Wait(val checkInMs: Long?) : Decision()
        data class PlayNext(val reason: String) : Decision()
    }

    var kind: Kind = Kind.Filler
        private set

    private var track: String? = null
    private var last: Observation? = null

    /** Forget the current track, e.g. when a party starts. Whatever is playing becomes filler. */
    fun reset() {
        kind = Kind.Filler
        track = null
        last = null
    }

    /** A queued song has started and is playing now. */
    fun startedRequested(now: Observation) = claim(now, Kind.Requested)

    /** The host started a song themselves (e.g. "Play now" from a remote). */
    fun startedHostPick(now: Observation) = claim(now, Kind.HostPick)

    private fun claim(now: Observation, newKind: Kind) {
        kind = newKind
        track = now.track
        last = now
    }

    fun observe(o: Observation, hasNext: Boolean): Decision {
        val previous = last
        last = o

        if (o.track != track) {
            val hadTrack = track != null
            track = o.track
            kind = when {
                o.track == null -> Kind.Filler
                !hadTrack || previous == null -> Kind.Filler
                endedNaturally(previous, o.at) -> Kind.Filler
                else -> Kind.HostPick
            }
            if (kind == Kind.Filler && hasNext) {
                return Decision.PlayNext(if (hadTrack) "the previous song ended" else "nothing requested was playing")
            }
        }

        if (!hasNext) return Decision.Wait(null)

        return when (kind) {
            Kind.Filler -> Decision.PlayNext("nothing requested was playing")
            Kind.Requested, Kind.HostPick -> when {
                finished(o) -> Decision.PlayNext("the song finished")
                o.playback != PLAYING -> Decision.Wait(null)
                else -> {
                    val remaining = remaining(o) ?: return Decision.Wait(null)
                    if (remaining <= preemptMs) Decision.PlayNext("the song is ending")
                    else Decision.Wait(remaining - preemptMs)
                }
            }
        }
    }

    private fun remaining(o: Observation): Long? {
        val duration = o.durationMs?.takeIf { it > 0 } ?: return null
        val position = o.positionMs ?: return null
        return (duration - position).coerceAtLeast(0)
    }

    /** Stopped for good, or paused right at the end (autoplay off). */
    private fun finished(o: Observation): Boolean {
        if (o.playback == STOPPED || o.playback == NONE) return true
        if (o.playback == PLAYING || o.playback == BUFFERING) return false
        val remaining = remaining(o) ?: return false
        return remaining <= FINISHED_SLACK_MS
    }

    /** Whether the previous track was at its end when it changed at [changedAt]. */
    private fun endedNaturally(previous: Observation, changedAt: Long): Boolean {
        // No length or position to go by: assume it ended, so the queue keeps moving.
        val duration = previous.durationMs?.takeIf { it > 0 } ?: return true
        val position = previous.positionMs ?: return true
        val projected = if (previous.playback == PLAYING) position + (changedAt - previous.at) else position
        return projected >= duration - endWindowMs
    }

    companion object {
        private const val PLAYING = "playing"
        private const val BUFFERING = "buffering"
        private const val STOPPED = "stopped"
        private const val NONE = "none"
        private const val FINISHED_SLACK_MS = 1_500L
    }
}
