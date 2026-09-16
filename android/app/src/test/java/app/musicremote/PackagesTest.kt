package app.musicremote

import org.junit.Assert.assertEquals
import org.junit.Test

class PackagesTest {

    @Test
    fun recognisesPatchedYouTubeMusicBuilds() {
        assertEquals(0, Packages.rank("app.morphe.android.apps.youtube.music"))
        assertEquals(0, Packages.rank("app.revanced.android.apps.youtube.music"))
        assertEquals(0, Packages.rank(Packages.YTM_STOCK))
    }

    @Test
    fun ranksOtherSources() {
        assertEquals(1, Packages.rank("app.morphe.android.youtube"))
        assertEquals(2, Packages.rank("com.spotify.music"))
        assertEquals(9, Packages.rank("com.android.server.telecom"))
        assertEquals(9, Packages.rank(null))
    }

    @Test
    fun labelsIgnoreWhoBuiltTheApp() {
        assertEquals("YouTube Music", Packages.label("app.morphe.android.apps.youtube.music"))
        assertEquals("YouTube", Packages.label("app.morphe.android.youtube"))
        assertEquals("telecom", Packages.label("com.android.server.telecom"))
        assertEquals("", Packages.label(null))
    }
}
