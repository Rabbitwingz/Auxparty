package app.auxparty.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import app.auxparty.SearchResult
import app.auxparty.party.QueueItem
import app.auxparty.party.Requester
import app.auxparty.ui.state.Connection
import app.auxparty.ui.state.GuestUi
import app.auxparty.ui.state.PartyUi
import app.auxparty.ui.state.HostUiState
import app.auxparty.ui.state.LinkedBrowserUi
import app.auxparty.ui.state.NowPlayingUi
import app.auxparty.ui.state.PairCodeUi
import app.auxparty.ui.state.SetupState
import app.auxparty.ui.theme.AuxpartyTheme
import app.auxparty.ui.theme.seedFromArtwork
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

    val results = listOf(
        SearchResult("r1", "Neon Rain", "Velvet Static", "Night Signals", "3:42", null),
        SearchResult("r2", "Neon Rain (Acoustic)", "Velvet Static", "Night Signals (Deluxe)", "4:05", null),
        SearchResult("r3", "Rain on Neon Streets", "Harbour Lights", "Tidal", "3:18", null),
        SearchResult("r4", "Neon", "Moth Parade", "Paper Planets", "2:57", null),
        SearchResult("r5", "After the Rain", "The Midnight Arcade", "Golden Hour", "5:11", null),
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
