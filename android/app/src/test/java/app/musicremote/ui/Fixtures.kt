package app.musicremote.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import app.musicremote.party.QueueItem
import app.musicremote.party.Requester
import app.musicremote.ui.state.Connection
import app.musicremote.ui.state.GuestUi
import app.musicremote.ui.state.PartyUi
import app.musicremote.ui.state.HostUiState
import app.musicremote.ui.state.LinkedBrowserUi
import app.musicremote.ui.state.NowPlayingUi
import app.musicremote.ui.state.PairCodeUi
import app.musicremote.ui.state.SetupState
import app.musicremote.ui.theme.AuxpartyTheme
import app.musicremote.ui.theme.seedFromArtwork
import com.github.takahirom.roborazzi.captureRoboImage

/** Fixed "now" so relative times and countdowns render identically every run. */
const val NOW = 1_789_560_000_000L

object Fixtures {
    val allGranted = SetupState(true, true, true, true, "app.morphe.android.apps.youtube.music")

    fun playing(art: Bitmap?, title: String, artist: String, playing: Boolean = true) = NowPlayingUi(
        title = title,
        artist = artist,
        source = "YouTube Music",
        artwork = art?.asImageBitmap(),
        playing = playing,
        positionMs = 83_000,
        durationMs = 231_000,
        volume = 9,
        maxVolume = 15,
    )

    val browsers = listOf(
        LinkedBrowserUi("a", "Chrome on Windows", NOW - 3 * 86_400_000L, NOW - 2 * 3_600_000L),
        LinkedBrowserUi("b", "Safari on iOS", NOW - 86_400_000L, NOW - 5 * 60_000L),
        LinkedBrowserUi("c", "Firefox on Linux", NOW - 20 * 60_000L, null),
    )

    private fun request(id: String, title: String, artist: String, vararg names: String) = QueueItem(
        itemId = id,
        videoId = "vid$id",
        title = title,
        artist = artist,
        album = null,
        durationMs = 200_000,
        thumbnail = null,
        requestedBy = names.map { Requester("g$it", it, "guest") },
        addedAt = NOW,
    )

    val party = PartyUi(
        active = true,
        link = "https://auxparty.vercel.app/#party=bece7e72428a2a1df357b05946.0SJhmJDBRYxXNpgcEqf9R38jv1JV-CZSUKgQdcNPNHw",
        guests = listOf(
            GuestUi("gSam", "Sam", NOW - 42 * 60_000L),
            GuestUi("gPriya", "Priya", NOW - 15 * 60_000L),
            GuestUi("gJo", "Jo", NOW - 30_000L),
        ),
        current = request("0", "Golden Hour Drive", "The Midnight Arcade", "Priya"),
        upcoming = listOf(
            request("1", "Neon Rain", "Velvet Static", "Sam", "Jo"),
            request("2", "Slow Burn (Extended Mix)", "Harbour Lights", "Priya"),
            request("3", "Paper Planets", "Moth Parade", "Jo"),
            request("4", "Afterglow", "The Midnight Arcade", "Sam"),
        ),
    )

    fun state(
        art: Bitmap? = null,
        nowPlaying: NowPlayingUi? = null,
        connection: Connection = Connection.Online,
        linked: List<LinkedBrowserUi> = browsers,
        setup: SetupState = allGranted,
        pairCode: PairCodeUi? = null,
        justLinked: String? = null,
    ) = HostUiState(
        connection = connection,
        nowPlaying = nowPlaying,
        seed = art?.let(::seedFromArtwork),
        linked = linked,
        pairCode = pairCode,
        justLinked = justLinked,
        setup = setup,
        onboardingDone = true,
    )
}

/**
 * Renders [content] in the Auxparty theme and saves a PNG. Screens with endless
 * animations (spinning artwork, waves) never go idle, so the clock is driven by hand.
 */
fun ComposeContentTestRule.shoot(
    name: String,
    state: HostUiState,
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    mainClock.autoAdvance = false
    setContent { AuxpartyTheme(seed = state.seed, darkTheme = dark) { content() } }
    mainClock.advanceTimeBy(1_500)
    onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
}
