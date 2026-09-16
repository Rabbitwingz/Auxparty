package app.auxparty.party

import app.auxparty.party.QueueEngine.Decision
import app.auxparty.party.QueueEngine.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEngineTest {

    private val engine = QueueEngine(endWindowMs = 4_000, preemptMs = 800)

    private fun obs(
        id: String?,
        at: Long,
        positionMs: Long? = 0,
        playback: String = "playing",
        durationMs: Long? = 200_000,
    ) = QueueEngine.Observation(mediaId = id, title = id?.let { "Title $it" }, playback = playback, positionMs = positionMs, durationMs = durationMs, at = at)

    private fun assertPlayNext(d: Decision) = assertTrue("expected PlayNext, got $d", d is Decision.PlayNext)

    @Test
    fun whateverPlaysWhenThePartyStartsIsFillerAndARequestCutsIn() {
        assertEquals(Decision.Wait(null), engine.observe(obs("leftover", at = 0, positionMs = 30_000), hasNext = false))
        assertEquals(Kind.Filler, engine.kind)
        assertPlayNext(engine.observe(obs("leftover", at = 5_000, positionMs = 35_000), hasNext = true))
    }

    @Test
    fun nothingPlayingStartsTheFirstRequest() {
        assertPlayNext(engine.observe(obs(null, at = 0, positionMs = null, playback = "none"), hasNext = true))
    }

    @Test
    fun aRequestedSongPlaysToTheEndThenTheNextStartsJustBefore() {
        engine.startedRequested(obs("song1", at = 0))
        assertEquals(Decision.Wait(200_000 - 800), engine.observe(obs("song1", at = 0), hasNext = true))
        assertEquals(Decision.Wait(100_000 - 800), engine.observe(obs("song1", at = 100_000, positionMs = 100_000), hasNext = true))
        assertPlayNext(engine.observe(obs("song1", at = 199_300, positionMs = 199_300), hasNext = true))
    }

    @Test
    fun withNoRequestsWaitingNothingHappens() {
        engine.startedRequested(obs("song1", at = 0))
        assertEquals(Decision.Wait(null), engine.observe(obs("song1", at = 199_500, positionMs = 199_500), hasNext = false))
    }

    @Test
    fun missedTheEndAndAutoplayTookOverSoTheQueueCutsIn() {
        engine.startedRequested(obs("song1", at = 0))
        engine.observe(obs("song1", at = 190_000, positionMs = 190_000), hasNext = true)
        // The phone slept through the timer; the next thing seen is YouTube Music's autoplay.
        assertPlayNext(engine.observe(obs("autoplay", at = 203_000, positionMs = 2_000), hasNext = true))
        assertEquals(Kind.Filler, engine.kind)
    }

    @Test
    fun autoplayChainsStayFillerUntilSomeoneRequests() {
        engine.startedRequested(obs("song1", at = 0))
        engine.observe(obs("song1", at = 199_000, positionMs = 199_000), hasNext = false)
        assertEquals(Decision.Wait(null), engine.observe(obs("auto1", at = 201_000), hasNext = false))
        assertEquals(Decision.Wait(null), engine.observe(obs("auto2", at = 401_500, positionMs = 500), hasNext = false))
        assertEquals(Kind.Filler, engine.kind)
        assertPlayNext(engine.observe(obs("auto2", at = 410_000, positionMs = 9_000), hasNext = true))
    }

    @Test
    fun theHostPickingASongMidSongIsLeftToFinish() {
        engine.startedRequested(obs("song1", at = 0))
        engine.observe(obs("song1", at = 60_000, positionMs = 60_000), hasNext = true)
        // Changed a minute in, far from the end: the host chose this.
        val d = engine.observe(obs("hostPick", at = 62_000), hasNext = true)
        assertEquals(Kind.HostPick, engine.kind)
        assertEquals(Decision.Wait(200_000 - 800), d)
        assertPlayNext(engine.observe(obs("hostPick", at = 261_500, positionMs = 199_500), hasNext = true))
    }

    @Test
    fun playNowFromARemoteIsAHostPick() {
        engine.observe(obs("leftover", at = 0), hasNext = false)
        engine.startedHostPick(obs("picked", at = 1_000))
        assertEquals(Decision.Wait(200_000 - 800), engine.observe(obs("picked", at = 1_000), hasNext = true))
    }

    @Test
    fun pausedMidSongWaitsButPausedAtTheEndOrStoppedMovesOn() {
        engine.startedRequested(obs("song1", at = 0))
        assertEquals(Decision.Wait(null), engine.observe(obs("song1", at = 50_000, positionMs = 50_000, playback = "paused"), hasNext = true))
        assertPlayNext(engine.observe(obs("song1", at = 200_000, positionMs = 199_000, playback = "paused"), hasNext = true))

        engine.startedRequested(obs("song2", at = 0))
        assertPlayNext(engine.observe(obs("song2", at = 10_000, positionMs = 10_000, playback = "stopped"), hasNext = true))
    }

    @Test
    fun bufferingIsNotTheEnd() {
        engine.startedRequested(obs("song1", at = 0))
        assertEquals(Decision.Wait(null), engine.observe(obs("song1", at = 5_000, positionMs = 200_000, playback = "buffering"), hasNext = true))
    }

    @Test
    fun seekingReschedulesTheCheck() {
        engine.startedRequested(obs("song1", at = 0))
        engine.observe(obs("song1", at = 10_000, positionMs = 10_000), hasNext = true)
        assertEquals(Decision.Wait(20_000 - 800), engine.observe(obs("song1", at = 11_000, positionMs = 180_000), hasNext = true))
    }

    @Test
    fun unknownLengthMeansATrackChangeKeepsTheQueueMoving() {
        engine.startedRequested(obs("song1", at = 0, durationMs = null))
        assertEquals(Decision.Wait(null), engine.observe(obs("song1", at = 30_000, positionMs = 30_000, durationMs = null), hasNext = true))
        assertPlayNext(engine.observe(obs("next", at = 31_000), hasNext = true))
    }

    @Test
    fun resetTreatsTheCurrentTrackAsFiller() {
        engine.startedRequested(obs("song1", at = 0))
        engine.reset()
        assertPlayNext(engine.observe(obs("song1", at = 1_000, positionMs = 1_000), hasNext = true))
    }
}
