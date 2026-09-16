package app.auxparty

import app.auxparty.BackgroundPlayer.Companion.trackMatches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlayerTest {

    @Test
    fun matchesOnMediaIdWhenYouTubeMusicUsesTheVideoId() {
        assertTrue(trackMatches("RvegizX3GqY", "Numb", "RvegizX3GqY", null, allowTitle = false))
        // Some builds prefix or wrap the id.
        assertTrue(trackMatches("track/RvegizX3GqY?x=1", null, "RvegizX3GqY", null, allowTitle = false))
    }

    @Test
    fun aRemixWithASimilarTitleIsNotTheRequestedTrack() {
        // Picking "Numb" while "Numb (Chill Mix)" plays must not count as success.
        assertFalse(trackMatches("nn2t_MjN9a4", "Numb (Chill Mix)", "RvegizX3GqY", "Numb", allowTitle = false))
        assertFalse(trackMatches(null, "Numb (Chill Mix)", "RvegizX3GqY", "Numb", allowTitle = true))
    }

    @Test
    fun titlesOnlyCountWhenAllowed() {
        assertFalse(trackMatches("some-other-id", "Numb", "RvegizX3GqY", "Numb", allowTitle = false))
        assertTrue(trackMatches("some-other-id", "Numb", "RvegizX3GqY", "Numb", allowTitle = true))
    }

    @Test
    fun titleMatchIgnoresCaseAndPunctuation() {
        assertTrue(trackMatches(null, "Instant Crush (feat. Julian Casablancas)", "khnokW3Mw24", "instant crush feat julian casablancas"))
    }

    @Test
    fun missingInformationIsNotAMatch() {
        assertFalse(trackMatches(null, null, "RvegizX3GqY", "Numb"))
        assertFalse(trackMatches(null, "Numb", "RvegizX3GqY", null))
        assertFalse(trackMatches(null, "U", "RvegizX3GqY", "U2"))
    }
}
