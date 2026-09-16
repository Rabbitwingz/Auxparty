package app.auxparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatePublisherTest {

    private fun playing(
        title: String = "Numb",
        positionMs: Long? = 10_000,
        state: String = "playing",
        volume: Int = 5,
    ) = NowPlaying(
        packageName = "app.morphe.android.apps.youtube.music",
        source = "YouTube Music",
        title = title,
        artist = "Elderbrook",
        album = "Why Do We Shake In The Cold?",
        durationMs = 231_000,
        positionMs = positionMs,
        state = state,
        volume = volume,
        maxVolume = 25,
    )

    @Test
    fun firstSnapshotSendsStateAndArtwork() {
        assertEquals(StatePublisher.Decision(state = true, artwork = true), StatePublisher().decide(playing(), 0))
    }

    @Test
    fun steadyPlaybackIsNotResent() {
        val p = StatePublisher()
        p.decide(playing(positionMs = 10_000), now = 0)
        // Two seconds later the song is two seconds further in: exactly as extrapolated.
        assertEquals(StatePublisher.Decision(state = false, artwork = false), p.decide(playing(positionMs = 12_000), now = 2_000))
        assertFalse(p.decide(playing(positionMs = 70_000), now = 60_000).state)
    }

    @Test
    fun seekingIsSent() {
        val p = StatePublisher()
        p.decide(playing(positionMs = 10_000), now = 0)
        assertTrue(p.decide(playing(positionMs = 90_000), now = 2_000).state)
    }

    @Test
    fun pausingIsSentAndPausedPositionDoesNotDrift() {
        val p = StatePublisher()
        p.decide(playing(positionMs = 10_000), now = 0)
        assertTrue(p.decide(playing(positionMs = 12_000, state = "paused"), now = 2_000).state)
        // While paused, position stays put; minutes later that is still no change.
        assertFalse(p.decide(playing(positionMs = 12_000, state = "paused"), now = 300_000).state)
    }

    @Test
    fun volumeChangeIsSentWithoutArtwork() {
        val p = StatePublisher()
        p.decide(playing(), now = 0)
        assertEquals(StatePublisher.Decision(state = true, artwork = false), p.decide(playing(positionMs = 11_000, volume = 6), now = 1_000))
    }

    @Test
    fun newTrackSendsArtwork() {
        val p = StatePublisher()
        p.decide(playing(title = "Numb"), now = 0)
        val d = p.decide(playing(title = "Inner Light", positionMs = 0), now = 1_000)
        assertTrue(d.state)
        assertTrue(d.artwork)
    }

    @Test
    fun resetResendsEverything() {
        val p = StatePublisher()
        p.decide(playing(), now = 0)
        p.reset()
        assertEquals(StatePublisher.Decision(state = true, artwork = true), p.decide(playing(positionMs = 11_000), now = 1_000))
    }
}
